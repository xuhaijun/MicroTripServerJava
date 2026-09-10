#!/usr/bin/env bash
# ============================================================
# 数据导入：SQL 文件 / CSV（过渡表模式，边导边校验）
# ------------------------------------------------------------
# 用法：
#   ./scripts/db-import.sh --sql backup.sql           # 整库/单表 SQL 恢复
#   ./scripts/db-import.sh --csv users.csv --table users --columns phone,nickname,role \
#       --set "id=CONCAT('u_imp_',LPAD(ROW_NUMBER() OVER (),6,'0'))" \
#       --set "created_at=UNIX_TIMESTAMP()*1000"
#   ./scripts/db-import.sh --jsonl trajs_demo.jsonl   # 轨迹 JSONL 走接口导入（推荐）
#   ./scripts/db-import.sh --jsonl xx.jsonl --base-url http://127.0.0.1:3000
#
# CSV 导入设计（--csv 模式）：
#   ① 先入**临时过渡表**（全 varchar），坏行不会污染正式表也不会半途报错；
#   ② 展示过渡表前 5 行让你人工核对；
#   ③ 再按 --columns 复制进正式表。三步都看到结果才动真数据。
#   表头跳过 1 行；分隔符 TAB（配合 db-export.sh --batch 产物）。
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

DB_NAME="${DB_NAME:-microtrip}"
DB_USER="${DB_USERNAME:-microtrip}"
DB_PASS="${DB_PASSWORD:-microtrip123}"
HOST="${DB_HOST:-127.0.0.1}"
PORT="${DB_PORT:-3306}"

SQL_FILE="" CSV_FILE="" TARGET_TABLE="" COLUMNS="" JSONL_FILE="" BASE_URL="http://127.0.0.1:3000"
SET_EXPRS=()
IMP_PHONE="" IMP_PASS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --sql)    SQL_FILE="$2";  shift 2 ;;
        --csv)    CSV_FILE="$2";  shift 2 ;;
        --table)  TARGET_TABLE="$2"; shift 2 ;;
        --columns) COLUMNS="$2";  shift 2 ;;
        --jsonl)  JSONL_FILE="$2"; shift 2 ;;
        --base-url) BASE_URL="$2"; shift 2 ;;
        --phone)  IMP_PHONE="$2"; shift 2 ;;   # JSONL 模式：导入到该用户（避免交互）
        --set)    SET_EXPRS+=("$2"); shift 2 ;;   # CSV 模式：补常量/表达式列，如 --set "role='USER'"（可重复）
        --password) IMP_PASS="$2"; shift 2 ;;
        -h|--help) sed -n '1,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done

# 客户端探测统一用 _common.sh 的 resolve_cli（支持 MYSQL_BIN 反推同目录其他工具）

MYSQL_BIN_RESOLVED="$(resolve_cli mysql)" || die "未找到 mysql 客户端（可设 MYSQL_BIN）"
MYSQL=("$MYSQL_BIN_RESOLVED" -h"$HOST" -P"$PORT" -u"$DB_USER" -p"$DB_PASS"
       --default-character-set=utf8mb4 "$DB_NAME")

# ---------------- 模式一：SQL 文件恢复 ----------------
if [ -n "$SQL_FILE" ]; then
    [ -f "$SQL_FILE" ] || die "文件不存在：$SQL_FILE"
    warn "即将把 $SQL_FILE 导入 $DB_NAME（若含 DROP/CREATE 会覆盖现有数据）"
    read -r -p "确认继续？[y/N] " ans
    [ "$ans" = "y" ] || die "已取消"
    info "导入中..."
    "${MYSQL[@]}" < "$SQL_FILE"
    ok "完成。核对：SELECT COUNT(*) FROM trajectories;"
    exit 0
fi

# ---------------- 模式二：CSV 过渡表导入 ----------------
if [ -n "$CSV_FILE" ]; then
    [ -f "$CSV_FILE" ]           || die "文件不存在：$CSV_FILE"
    [ -n "$TARGET_TABLE" ]       || die "缺 --table（正式表名）"
    [ -n "$COLUMNS" ]            || die "缺 --columns（如 phone,nickname,role）"
    # 路径转 Windows 形式（LOAD DATA 在 Windows 服务端按 Win 路径解析）
    WIN_PATH=$(cygpath -w "$CSV_FILE" 2>/dev/null | sed 's|\\|/|g' || echo "$CSV_FILE")

    TMP="tmp_import_$(date +%s)"
    # 先清历史残留：上次导入中途失败（set -e 直接退出）会留下旧过渡表。
    # tr -d '\r'：Windows mysql.exe 输出 CRLF，表名带 \r 会让 DROP 静默落空
    LEFTOVER="$("${MYSQL[@]}" -N -e "SHOW TABLES LIKE 'tmp_import_%';" 2>/dev/null | tr -d '\r')"
    if [ -n "$LEFTOVER" ]; then
        warn "发现历史残留过渡表：$LEFTOVER（自动清理）"
        for t in $LEFTOVER; do "${MYSQL[@]}" -e "DROP TABLE IF EXISTS \`$t\`;"; done
    fi
    info "① 建过渡表 $TMP（全 varchar，坏数据进不了正式表）"
    "${MYSQL[@]}" -e "DROP TABLE IF EXISTS \`$TMP\`;
        CREATE TABLE \`$TMP\` (_c0 varchar(512),_c1 varchar(512),_c2 varchar(512),
        _c3 varchar(512),_c4 varchar(512),_c5 varchar(512),_c6 varchar(512),_c7 varchar(512));"

    info "② LOAD DATA（TAB 分隔，跳过表头；需 local_infile 已开启）"
    # 预检：local_infile 未开时给出可操作提示，而不是甩一个 Access denied
    if [ "$("${MYSQL[@]}" -N -e "SELECT @@local_infile;")" != "1" ]; then
        err "服务器 local_infile 未开启。用 root 执行一次："
        dim  "  \"$MYSQL_BIN_RESOLVED\" -uroot -p -e \"SET GLOBAL local_infile=1;\""
        dim  "  或重跑 ./scripts/db-init.sh（会顺带开启）；永久生效写 my.ini [mysqld] local_infile=1"
        "${MYSQL[@]}" -e "DROP TABLE IF EXISTS \`$TMP\`;"
        exit 1
    fi
    "${MYSQL[@]}" --local-infile=1 -e "
        LOAD DATA LOCAL INFILE '$WIN_PATH' INTO TABLE \`$TMP\`
        CHARACTER SET utf8mb4 FIELDS TERMINATED BY '\t'
        LINES TERMINATED BY '\n' IGNORE 1 LINES;"

    info "③ 过渡表前 5 行预览（核对列顺序与内容）"
    "${MYSQL[@]}" --batch -e "SELECT * FROM \`$TMP\` LIMIT 5;"
    dim  "列数上限 8；不足的列会是 NULL，属正常。"

    warn "即将把过渡表 ($TMP) 的列 [$COLUMNS] 复制进正式表 `$TARGET_TABLE`"
    read -r -p "确认继续？[y/N] " ans
    [ "$ans" = "y" ] || { "${MYSQL[@]}" -e "DROP TABLE \`$TMP\`"; die "已取消（过渡表已清理）"; }

    # 列映射：INSERT INTO t (a,b,c,extra...) SELECT _c0,_c1,_c2,expr... FROM tmp
    #   （--columns 顺序对应 _c0.._cN；--set 追加常量/表达式列，补 id/时间戳等必填项）
    INSERT_COLS="" MAP=""
    i=0
    IFS=',' read -r -a COLS <<< "$COLUMNS"
    for c in "${COLS[@]}"; do
        [ -n "$INSERT_COLS" ] && INSERT_COLS+=", " && MAP+=", "
        INSERT_COLS+="\`$c\`"
        MAP+="_c$i"
        i=$((i+1))
    done
    for e in "${SET_EXPRS[@]:-}"; do
        col="${e%%=*}"; expr="${e#*=}"
        INSERT_COLS+=", \`$col\`"
        MAP+=", $expr"
    done
    "${MYSQL[@]}" -e "INSERT INTO \`$TARGET_TABLE\` ($INSERT_COLS) SELECT $MAP FROM \`$TMP\`;"
    TOTAL=$("${MYSQL[@]}" -N -e "SELECT COUNT(*) FROM \`$TARGET_TABLE\`;")
    ok "导入完成：$TARGET_TABLE 现共 $TOTAL 行"
    "${MYSQL[@]}" -e "DROP TABLE \`$TMP\`;"
    dim  "过渡表已清理。若正式表有唯一键，重复行会在这一步报错回滚（属预期保护）。"
    exit 0
fi

# ---------------- 模式三：轨迹 JSONL 走接口（推荐，双写一致） ----------------
if [ -n "$JSONL_FILE" ]; then
    [ -f "$JSONL_FILE" ] || die "文件不存在：$JSONL_FILE"
    command -v python >/dev/null || die "需要 python"
    [ -n "$IMP_PHONE" ] || die "缺 --phone（导入到哪个已注册用户）"
    [ -n "$IMP_PASS" ]  || die "缺 --password"
    info "走 /api/v1 接口导入（pts 键名等契约由服务端校验，杜绝静默 0 点）"
    # 注意：python 代码必须走 -c 传参，绝不能用 heredoc（会占掉 stdin，参数进不去）
    python -c '
import json, sys, urllib.request
path, base, phone, pw = sys.argv[1], sys.argv[2].rstrip("/"), sys.argv[3], sys.argv[4]
def post(p, body, token=""):
    req = urllib.request.Request(base+p, method="POST",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    return json.loads(urllib.request.urlopen(req, timeout=15).read().decode())
tok = post("/auth/login", {"phone": phone, "password": pw}).get("token", "")
if not tok: sys.exit("✗ 登录失败（检查账号密码/服务是否可达）")
n = 0
for line in open(path, encoding="utf-8"):
    line = line.strip()
    if not line: continue
    r = post("/trajectory/sync", {"trajectory": json.loads(line)}, tok)
    if not r.get("ok"): sys.exit("✗ 同步被拒：" + str(r))
    n += 1
    print("  #%d %s ✓ %s 点" % (n, r.get("id"), r.get("points", 0)))
print("完成：%d 条轨迹" % n)
' "$JSONL_FILE" "$BASE_URL" "$IMP_PHONE" "$IMP_PASS"
    exit 0
fi

sed -n '1,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
exit 1
