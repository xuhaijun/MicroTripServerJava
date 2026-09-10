#!/usr/bin/env bash
# ============================================================
# MySQL 数据库初始化（建库建用户，幂等可重复执行）
# ------------------------------------------------------------
# 用法：
#   ./scripts/db-init.sh                          # 默认 root 无密码（本机 initialize-insecure 装法）
#   ./scripts/db-init.sh -r rootroot              # 指定 root 密码
#   ./scripts/db-init.sh --host 10.0.0.5 --port 3306
#   ./scripts/db-init.sh --container microtrip-mysql   # 走 docker exec（容器部署）
#
# 做什么：
#   1. 建库 microtrip（utf8mb4，幂等：IF NOT EXISTS）
#   2. 建业务账号 microtrip（幂等；已存在则只刷新密码）
#   3. 授权并核对
#
# 不做什么：
#   建表！表结构由应用启动时 JPA ddl-auto 自动创建（列类型与实体永远一致）。
#   本脚本跑完后，起一次应用即完成建表：
#     ./scripts/dev.sh && curl -s localhost:3000/api/v1/health
#
# 依赖探测顺序（可环境变量覆盖 MYSQL_BIN）：
#   $MYSQL_BIN → 本机常见安装路径 → PATH 里的 mysql → docker exec
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

DB_NAME="${DB_NAME:-microtrip}"
DB_USER="${DB_USERNAME:-microtrip}"
DB_PASS="${DB_PASSWORD:-microtrip123}"
ROOT_PASS=""
HOST="127.0.0.1"
PORT="${DB_PORT:-3306}"
CONTAINER=""

while [ $# -gt 0 ]; do
    case "$1" in
        -r|--root-pass) ROOT_PASS="$2"; shift 2 ;;
        --host) HOST="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --container) CONTAINER="$2"; shift 2 ;;
        -h|--help) sed -n '1,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done

# ---------------- 定位 mysql 客户端 ----------------
# 返回值写入全局 MYSQL_CMD（数组形式，容器模式为 docker exec ... mysql）
resolve_mysql() {
    # 显式指定优先
    if [ -n "${MYSQL_BIN:-}" ] && [ -x "${MYSQL_BIN:-}" ]; then
        MYSQL_CMD=("$MYSQL_BIN"); return 0
    fi
    # 容器模式
    if [ -n "$CONTAINER" ]; then
        command -v docker >/dev/null || die "未找到 docker"
        MYSQL_CMD=(docker exec -i "$CONTAINER" mysql); return 0
    fi
    # Windows 常见安装路径
    for p in \
        "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" \
        "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
        "/c/Program Files/MySQL/MySQL Server 5.7/bin/mysql.exe"; do
        [ -f "$p" ] && { MYSQL_CMD=("$p"); return 0; }
    done
    # PATH 兜底
    if command -v mysql >/dev/null; then
        MYSQL_CMD=(mysql); return 0
    fi
    return 1
}

if ! resolve_mysql; then
    die "未找到 mysql 客户端。设置 MYSQL_BIN 或用 --container 指定容器。"
fi

ROOT_ARGS=(-h"$HOST" -P"$PORT" -uroot)
[ -n "$ROOT_PASS" ] && ROOT_ARGS+=(-p"$ROOT_PASS")

sql_root() { "${MYSQL_CMD[@]}" "${ROOT_ARGS[@]}" --default-character-set=utf8mb4 "$@"; }

# ---------------- 1. 连通性预检 ----------------
title "MySQL 初始化（$HOST:$PORT → $DB_NAME）"
if ! sql_root -e "SELECT 1;" >/dev/null 2>&1; then
    err "root 连接失败。"
    dim  "  本机 initialize-insecure 装法：root 无密码，直接 ./scripts/db-init.sh"
    dim  "  正常安装：./scripts/db-init.sh -r <root密码>"
    exit 1
fi
ok "root 连接成功"

# ---------------- 2. 建库（幂等） ----------------
sql_root <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL
ok "数据库 \`$DB_NAME\` 就绪（utf8mb4_unicode_ci）"

# ---------------- 3. 建用户（幂等） + 授权 ----------------
# CREATE USER IF NOT EXISTS 在用户已存在时不改密码；开发环境密码固定，
# 存在时用 ALTER 刷新，保证「脚本跑完密码一定可用」。
sql_root <<SQL
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'localhost';
FLUSH PRIVILEGES;
SQL
ok "业务账号 '$DB_USER'@'localhost' 就绪（密码已同步为 DB_PASSWORD）"

# ---------------- 3.5 开 local_infile（CSV 导入用；需管理员权限） ----------------
# 该变量是服务器全局变量：MySQL 重启后失效。想永久生效写进 my.ini：
#   [mysqld] local_infile=1
# 托管数据库可能禁改全局变量：失败不阻断（只用 LOAD DATA 时才需要）。
if sql_root -e "SET GLOBAL local_infile=1;" 2>/dev/null; then
    ok "local_infile=1（CSV 导入可用；MySQL 重启后需重跑本脚本）"
else
    warn "local_infile 开启失败（权限不足）—— db-import.sh 的 --csv 模式将不可用，其余不受影响"
fi

# ---------------- 4. 用业务账号验收 ----------------
if "${MYSQL_CMD[@]}" -h"$HOST" -P"$PORT" -u"$DB_USER" -p"$DB_PASS" \
       --default-character-set=utf8mb4 -e "USE \`$DB_NAME\`; SELECT 1;" >/dev/null 2>&1; then
    ok "业务账号验收通过"
else
    die "业务账号验收失败（检查密码/权限）"
fi

echo ""
dim "下一步：起一次应用完成建表（JPA 自动建 users/trajectories/trajectory_points）"
dim "  ./scripts/dev.sh    然后    curl -s localhost:3000/api/v1/health"
