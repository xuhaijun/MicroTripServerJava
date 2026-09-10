#!/usr/bin/env bash
# ============================================================
# 数据库备份（本地 MySQL / Docker MySQL / H2 三模式，带轮转保留）
# ------------------------------------------------------------
# 用法：
#   ./scripts/backup.sh                     # 自动探测：有 Docker 容器走容器，否则走本地 mysqldump
#   ./scripts/backup.sh --local             # 强制本地模式（无 Docker 部署，默认 127.0.0.1:3306）
#   ./scripts/backup.sh --docker            # 强制容器模式（microtrip-mysql）
#   ./scripts/backup.sh --h2                # 备份本地 H2 文件库
#   ./scripts/backup.sh --keep 14           # 保留最近 14 份（默认 7）
#   ./scripts/backup.sh --dir /data/bak     # 自定义备份目录（默认 backups/）
#
# 为什么用 mysqldump 而不是直接拷数据目录：
#   直接拷 .ibd 文件在写入过程中会拿到不一致的快照（半个事务），
#   恢复后可能出现「轨迹存在但 GPS 点缺失」这类静默数据错误。
#   --single-transaction 能在 InnoDB 上取得一致性快照且不锁表。
#
# 账号来源：环境变量 / .env 的 DB_NAME / DB_USERNAME / DB_PASSWORD
#   （Docker 变量名 MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD 同样可识别）。
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

MODE="auto"
KEEP=7
BACKUP_DIR="backups"
CONTAINER="microtrip-mysql"
while [ $# -gt 0 ]; do
    case "$1" in
        --local)  MODE="local";  shift ;;
        --docker) MODE="docker"; shift ;;
        --h2)     MODE="h2";     shift ;;
        --keep)   KEEP="$2";     shift 2 ;;
        --dir)    BACKUP_DIR="$2"; shift 2 ;;
        -h|--help) sed -n '1,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done

load_dotenv .env
TS="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# ---------- 账号解析（兼容两套变量命名，Docker 优先） ----------
DB_NAME_FINAL="${MYSQL_DATABASE:-${DB_NAME:-microtrip}}"
DB_USER_FINAL="${MYSQL_USER:-${DB_USERNAME:-microtrip}}"
DB_PASS_FINAL="${MYSQL_PASSWORD:-${DB_PASSWORD:-microtrip123}}"

# ---------- 模式决策 ----------
resolve_mode() {
    case "$MODE" in
        auto)
            if command -v docker >/dev/null 2>&1 \
               && docker inspect "$CONTAINER" >/dev/null 2>&1; then
                echo "docker"
            elif command -v mysqldump >/dev/null 2>&1 \
               || [ -f "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysqldump.exe" ] \
               || [ -f "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe" ]; then
                echo "local"
            else
                die "未找到备份途径：既无 Docker 容器 $CONTAINER，也无本地 mysqldump"
            fi ;;
        *) echo "$MODE" ;;
    esac
}

# ---------- 产出产物统一校验 + 行数快照 ----------
verify_and_snapshot() {   # $1=OUT 文件  $2=mysql 探测函数名(空=跳过行数快照)
    local out="$1"
    [ -f "$out" ] || die "备份产物不存在：$out"
    # gzip 完整性：管道中途失败（mysqldump 报错/连接断开）会得到截断文件
    command -v gzip >/dev/null 2>&1 && ! gzip -t "$out" 2>/dev/null \
        && { rm -f "$out"; die "备份文件 gzip 校验失败（疑似中途截断），已删除，请重试"; }
    local size
    size="$(wc -c < "$out" | tr -d ' ')"
    [ "$size" -ge 1024 ] || { rm -f "$out"; die "备份文件异常（<1KB），请检查账号权限与库名"; }

    if [ -n "${2:-}" ]; then
        # 行数快照用精确 COUNT(*)：INFORMATION_SCHEMA.table_rows 对 InnoDB 只是
        # 估算值（可能偏差几十），恢复后逐表对比会误报。表少，逐个 COUNT 可接受。
        # info 文件名 = 备份文件名 + .info，与下方轮转清理的 "$f".info 严格对应
        local info="${out}.info" t
        : > "$info"
        while IFS= read -r t; do
            t="${t%$'\r'}"          # Windows mysql.exe 输出 CRLF，必须剥掉 \r 否则表名非法
            [ -n "$t" ] || continue
            # 显式带上库名：mysql 客户端此时尚未 USE 任何库，不带库名会报 No database selected
            echo "$t=$("$2" -N -e "SELECT COUNT(*) FROM \`$DB_NAME_FINAL\`.\`$t\`;" 2>/dev/null || echo '?')" >> "$info"
        done < <("$2" -N -e "SHOW TABLES FROM \`$DB_NAME_FINAL\`;" 2>/dev/null || true)
        if [ -s "$info" ]; then
            dim "  行数快照：$info（恢复后逐行对比确认完整）"
        else
            rm -f "$info"
        fi
    fi
    ok "备份完成：$out（$(du -h "$out" | cut -f1)）"
}

# ---------- 公共 mysqldump 参数 ----------
# --routines --triggers：存储过程/触发器一起带走，否则恢复后行为不一致
# --set-gtid-purged=OFF：避免恢复到另一实例报 GTID 冲突
# --no-tablespaces：业务账号无 PROCESS 权限时 mysqldump 8.0.21+ 会告警/失败
DUMP_OPTS=(--single-transaction --routines --triggers
           --set-gtid-purged=OFF --no-tablespaces
           --default-character-set=utf8mb4)

MODE_RESOLVED="$(resolve_mode)"
info "备份模式：$MODE_RESOLVED（库 $DB_NAME_FINAL，保留 $KEEP 份）"

if [ "$MODE_RESOLVED" = "h2" ]; then
    # H2 文件库：应用运行中不宜直接拷贝，先提示
    DB_FILE="data/microtrip.mv.db"
    [ -f "$DB_FILE" ] || die "未找到 $DB_FILE"
    if port_in_use "${SERVER_PORT:-3000}"; then
        warn "服务正在运行（端口 ${SERVER_PORT:-3000} 被占用）"
        warn "运行中拷贝 H2 文件可能得到不一致快照；建议先停服再备份"
    fi
    OUT="$BACKUP_DIR/microtrip-h2_$TS.mv.db.gz"
    gzip -c "$DB_FILE" > "$OUT"
    verify_and_snapshot "$OUT" ""

elif [ "$MODE_RESOLVED" = "docker" ]; then
    command -v docker >/dev/null 2>&1 || die "未安装 Docker"
    docker inspect "$CONTAINER" >/dev/null 2>&1 || die "容器 $CONTAINER 不存在，先执行 ./scripts/infra.sh up"
    OUT="$BACKUP_DIR/microtrip-mysql_$TS.sql.gz"
    info "备份数据库 $DB_NAME_FINAL（容器内 mysqldump）…"
    docker exec "$CONTAINER" mysqldump "${DUMP_OPTS[@]}" \
        -u"$DB_USER_FINAL" -p"$DB_PASS_FINAL" "$DB_NAME_FINAL" 2>/dev/null | gzip > "$OUT"
    # 行数快照用容器内 mysql
    snap_mysql() {
        docker exec "$CONTAINER" mysql -u"$DB_USER_FINAL" -p"$DB_PASS_FINAL" "$@" 2>/dev/null
    }
    verify_and_snapshot "$OUT" snap_mysql
    dim "  恢复：gunzip -c $OUT | docker exec -i $CONTAINER mysql -u$DB_USER_FINAL -p<密码> $DB_NAME_FINAL"

else
    # 本地模式：直接用本机 mysqldump（无 Docker 部署）
    DUMPER="$(resolve_cli mysqldump)" || die "未找到本地 mysqldump（可设 MYSQL_BIN 指向 bin 目录）"
    MYSQLCLI="$(resolve_cli mysql)" || die "未找到本地 mysql 客户端"
    HOST="${DB_HOST:-127.0.0.1}"; PORT="${DB_PORT:-3306}"
    # 先确认库可达，给出比 mysqldump 报错更友好的提示
    "$MYSQLCLI" -h"$HOST" -P"$PORT" -u"$DB_USER_FINAL" -p"$DB_PASS_FINAL" \
        --default-character-set=utf8mb4 -N -e "SELECT 1;" >/dev/null 2>&1 \
        || die "无法连接 $HOST:$PORT（MySQL 未启动或账号不对；本地启动见《本地环境运维手册》§2）"
    OUT="$BACKUP_DIR/microtrip-mysql_$TS.sql.gz"
    info "备份数据库 $DB_NAME_FINAL@$HOST:$PORT（本地 mysqldump）…"
    "$DUMPER" -h"$HOST" -P"$PORT" -u"$DB_USER_FINAL" -p"$DB_PASS_FINAL" \
        "${DUMP_OPTS[@]}" "$DB_NAME_FINAL" 2>/dev/null | gzip > "$OUT"
    snap_mysql() {
        "$MYSQLCLI" -h"$HOST" -P"$PORT" -u"$DB_USER_FINAL" -p"$DB_PASS_FINAL" "$@" 2>/dev/null
    }
    verify_and_snapshot "$OUT" snap_mysql
    dim "  恢复：gunzip -c $OUT | mysql -h$HOST -P$PORT -u$DB_USER_FINAL -p $DB_NAME_FINAL"
    dim "  或用现成脚本：./scripts/db-import.sh --sql <解压后的 sql 文件>"
fi

# ---------------- 清理过期备份 ----------------
info "清理超过 $KEEP 份的历史备份…"
# shellcheck disable=SC2012
ls -1t "$BACKUP_DIR"/*.gz 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r f; do
    rm -f "$f" "$f".info "${f%.sql.gz}.info" "${f%.mv.db.gz}.info" 2>/dev/null || true
    dim "  已删除：$f"
done

echo
ok "备份目录：$BACKUP_DIR"
if is_windows; then
    dim "  Windows 定时备份（已注册则自动执行）："
    dim "    schtasks /Query /TN \"MicroTrip DB Backup\""
    dim "    手动注册：schtasks /Create /SC DAILY /ST 03:00 /TN \"MicroTrip DB Backup\" /TR \"<项目>\\scripts\\backup-task.bat\""
else
    dim "  建议加入 crontab：0 3 * * * cd $PROJECT_ROOT && ./scripts/backup.sh --keep 14 >> logs/backup.log 2>&1"
fi
