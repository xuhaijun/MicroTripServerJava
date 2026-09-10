#!/usr/bin/env bash
# ============================================================
# 数据库备份
# ------------------------------------------------------------
# 用法：
#   ./scripts/backup.sh                    # 备份本地 MySQL 容器
#   ./scripts/backup.sh --h2               # 备份本地 H2 文件库
#   ./scripts/backup.sh --keep 14          # 保留最近 14 份（默认 7）
#   ./scripts/backup.sh --dir /data/bak
#
# 为什么用 mysqldump 而不是直接拷数据目录：
#   直接拷 .ibd 文件在写入过程中会拿到不一致的快照（半个事务），
#   恢复后可能出现「轨迹存在但 GPS 点缺失」这类静默数据错误。
#   mysqldump 默认 --single-transaction 能在 InnoDB 上取得一致性快照且不锁表。
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

MODE="mysql"
KEEP=7
BACKUP_DIR="backups"
while [ $# -gt 0 ]; do
    case "$1" in
        --h2)   MODE="h2"; shift ;;
        --keep) KEEP="$2"; shift 2 ;;
        --dir)  BACKUP_DIR="$2"; shift 2 ;;
        -h|--help) sed -n '1,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done

load_dotenv .env
CONTAINER="microtrip-mysql"
TS="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

if [ "$MODE" = "h2" ]; then
    # H2 文件库：应用运行中不宜直接拷贝，先提示
    DB_FILE="data/microtrip.mv.db"
    [ -f "$DB_FILE" ] || die "未找到 $DB_FILE"
    if port_in_use "${SERVER_PORT:-3000}"; then
        warn "服务正在运行（端口 ${SERVER_PORT:-3000} 被占用）"
        warn "运行中拷贝 H2 文件可能得到不一致快照；建议先停服再备份"
        warn "如仅作临时排查使用，可继续"
    fi
    OUT="$BACKUP_DIR/microtrip-h2_$TS.mv.db.gz"
    gzip -c "$DB_FILE" > "$OUT"
    ok "H2 备份完成：$OUT（$(du -h "$OUT" | cut -f1)）"
else
    command -v docker >/dev/null 2>&1 || die "未安装 Docker"
    docker inspect "$CONTAINER" >/dev/null 2>&1 || die "容器 $CONTAINER 不存在，先执行 ./scripts/infra.sh up"

    DB_NAME="${MYSQL_DATABASE:-${DB_NAME:-microtrip}}"
    DB_USER="${MYSQL_USER:-${DB_USERNAME:-microtrip}}"
    DB_PASS="${MYSQL_PASSWORD:-${DB_PASSWORD:-microtrip123}}"

    OUT="$BACKUP_DIR/microtrip-mysql_$TS.sql.gz"
    info "备份数据库 $DB_NAME…"
    # --single-transaction：一致性快照且不锁表
    # --routines --triggers：把存储过程与触发器一起带走，否则恢复后行为不一致
    # --set-gtid-purged=OFF：避免恢复到另一实例时报 GTID 冲突
    docker exec "$CONTAINER" mysqldump \
        --single-transaction --routines --triggers --set-gtid-purged=OFF \
        --default-character-set=utf8mb4 \
        -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" 2>/dev/null | gzip > "$OUT"

    # 校验产物：gzip 损坏或 dump 中途失败都会得到极小文件
    SIZE_BYTES="$(wc -c < "$OUT" | tr -d ' ')"
    if [ "$SIZE_BYTES" -lt 1024 ]; then
        rm -f "$OUT"
        die "备份文件异常（<1KB），请检查数据库账号权限与库名"
    fi
    ok "MySQL 备份完成：$OUT（$(du -h "$OUT" | cut -f1)）"

    # 顺带把表行数记进同名 .info，恢复前可对比确认数据完整
    INFO="${OUT%.sql.gz}.info"
    docker exec "$CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -N -e "
        SELECT table_name, table_rows FROM information_schema.tables
        WHERE table_schema='$DB_NAME' ORDER BY table_name;" 2>/dev/null \
        | tee "$INFO" >/dev/null || true
    dim "  表行数快照：$INFO"
fi

# ---------------- 清理过期备份 ----------------
info "清理超过 $KEEP 份的历史备份…"
# shellcheck disable=SC2012
ls -1t "$BACKUP_DIR"/*.gz 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r f; do
    rm -f "$f" "${f%.sql.gz}.info" 2>/dev/null || true
    dim "  已删除：$f"
done

echo
ok "备份目录：$BACKUP_DIR"
dim "  恢复（MySQL）：gunzip -c <备份文件> | docker exec -i $CONTAINER mysql -u<用户> -p<密码> <库名>"
dim "  建议加入计划任务：0 3 * * * cd $PROJECT_ROOT && ./scripts/backup.sh --keep 14 >> logs/backup.log 2>&1"
