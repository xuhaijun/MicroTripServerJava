#!/usr/bin/env bash
# ============================================================
# 数据导出：CSV / 自定义 SQL 结果 / 轨迹 JSON / 整库
# ------------------------------------------------------------
# 用法：
#   ./scripts/db-export.sh --table users                 # 单表 CSV（TSV）
#   ./scripts/db-export.sh -s "SELECT phone,nickname FROM users"
#   ./scripts/db-export.sh --json demo                   # demo_% 轨迹导出为 JSON 文件
#   ./scripts/db-export.sh --dump                        # 整库 mysqldump（日常备份请用 backup.sh）
#
# 产出目录：exports/（时间戳文件名，避免互相覆盖）
#
# 为什么 CSV 用 --batch 重定向而不是 SELECT INTO OUTFILE：
#   OUTFILE 需要 FILE 权限且受 secure_file_priv 限制（服务器上常为空/只读目录）；
#   客户端 --batch 零权限要求，TAB 分隔 Excel/WPS 直接可开，幂等可重跑。
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}/../scripts")/_common.sh" 2>/dev/null \
    || source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

DB_NAME="${DB_NAME:-microtrip}"
DB_USER="${DB_USERNAME:-microtrip}"
DB_PASS="${DB_PASSWORD:-microtrip123}"
HOST="${DB_HOST:-127.0.0.1}"
PORT="${DB_PORT:-3306}"
OUT_DIR="exports"
TS="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_DIR"

TABLE="" SQL="" JSONPAT="" DUMP=""
while [ $# -gt 0 ]; do
    case "$1" in
        --table)  TABLE="$2";  shift 2 ;;
        -s|--sql) SQL="$2";    shift 2 ;;
        --json)   JSONPAT="$2"; shift 2 ;;
        --dump)   DUMP=1;      shift ;;
        -h|--help) sed -n '1,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done

# ---------------- 定位客户端（与 db-init.sh 同一探测顺序） ----------------
resolve_cli() {   # $1=程序名（mysql/mysqldump）
    local name="$1"
    local dir=""
    # 从 MYSQL_BIN 反推 bin 目录
    if [ -n "${MYSQL_BIN:-}" ] && [ -x "${MYSQL_BIN:-}" ]; then
        echo "$MYSQL_BIN" | sed "s|/mysql$|/$name|; s|/mysql\\.exe$|/$name.exe|"
        return 0
    fi
    for p in "/c/Program Files/MySQL/MySQL Server 8.4/bin" \
             "/c/Program Files/MySQL/MySQL Server 8.0/bin"; do
        [ -f "$p/$name.exe" ] && { echo "$p/$name.exe"; return 0; }
        [ -f "$p/$name" ]     && { echo "$p/$name";     return 0; }
    done
    command -v "$name" && return 0
    return 1
}

MYSQL_BIN_RESOLVED="$(resolve_cli mysql)" || die "未找到 mysql 客户端（可设 MYSQL_BIN）"
MYSQL=("$MYSQL_BIN_RESOLVED" -h"$HOST" -P"$PORT" -u"$DB_USER" -p"$DB_PASS"
       --default-character-set=utf8mb4 "$DB_NAME")

# ---------------- 1. 单表 CSV ----------------
if [ -n "$TABLE" ]; then
    OUT="$OUT_DIR/${TABLE}_${TS}.tsv"
    info "导出表 $TABLE → $OUT"
    "${MYSQL[@]}" --batch -e "SELECT * FROM \`$TABLE\`;" > "$OUT"
    ok "$(( $(wc -l < "$OUT") - 1 )) 行（首行为表头，TAB 分隔）"
    dim  "MySQL 客户端默认按 GBK 显示时中文可能乱码：请用 utf8 终端打开，"
    dim  "或直接读文件本身（文件内容是 UTF-8，与库一致）。"
    exit 0
fi

# ---------------- 2. 自定义 SQL → CSV ----------------
if [ -n "$SQL" ]; then
    OUT="$OUT_DIR/query_${TS}.tsv"
    info "执行查询 → $OUT"
    "${MYSQL[@]}" --batch -e "$SQL" > "$OUT"
    ok "$(wc -l < "$OUT") 行"
    exit 0
fi

# ---------------- 3. 轨迹导出 JSON（还原成客户端可再导入的格式） ----------------
if [ -n "$JSONPAT" ]; then
    OUT="$OUT_DIR/trajs_${JSONPAT}_${TS}.jsonl"
    info "导出轨迹（id LIKE '$JSONPAT%'）→ $OUT（JSONL：每行一条，客户端同步格式）"
    "${MYSQL[@]}" --batch --raw -N -e "
        SELECT JSON_OBJECT(
            'id', id, 'start', start_time, 'end', end_time,
            'distance', distance, 'duration', duration,
            'ascent', ascent, 'descent', descent, 'avgSpeed', avg_speed,
            'title', title, 'note', note, 'city', city, 'pts', CAST(points_json AS JSON))
        FROM trajectories WHERE id LIKE CONCAT('$JSONPAT', '%');" > "$OUT"
    ok "$(wc -l < "$OUT") 条轨迹（JSONL，每行一个 JSON 对象，键名与 /trajectory/sync 一致）"
    exit 0
fi

# ---------------- 4. 整库 dump ----------------
if [ -n "$DUMP" ]; then
    DUMPER="$(resolve_cli mysqldump)" || die "未找到 mysqldump"
    OUT="$OUT_DIR/full_${TS}.sql"
    info "整库导出 → $OUT"
    "$DUMPER" -h"$HOST" -P"$PORT" -u"$DB_USER" -p"$DB_PASS" \
        --default-character-set=utf8mb4 --single-transaction --no-tablespaces \
        "$DB_NAME" > "$OUT"
    ok "$(du -h "$OUT" | cut -f1)"
    dim  "日常定时备份请用 ./scripts/backup.sh（带轮转保留策略）"
    exit 0
fi

# ---------------- 无参数：展示帮助 ----------------
sed -n '1,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
exit 1
