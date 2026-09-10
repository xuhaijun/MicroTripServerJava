#!/usr/bin/env bash
# ============================================================
# 本地开发启动器（热重启友好，可挂调试器）
# ------------------------------------------------------------
# 用法：
#   ./scripts/dev.sh            # 默认：文件型 H2，零外部依赖，开箱即用
#   ./scripts/dev.sh mysql      # 连本地 MySQL 容器（需先 ./scripts/infra.sh up）
#   ./scripts/dev.sh full       # MySQL + Redis（贴近生产拓扑）
#
# 与 run-local.sh 的区别：
#   dev.sh       走 mvn spring-boot:run，改代码重启快，适合日常开发
#   run-local.sh 先用真实 jar 打包再跑，用于本地验证「和线上一致的产物」
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

MODE="${1:-h2}"
ensure_java_env

MVN_CMD="$(resolve_mvn)" || die "未找到 Maven。本机可装：winget install Apache.Maven"
require_jdk 21
load_dotenv .env

PORT="${SERVER_PORT:-3000}"

# ---------------- 组装 profile 与数据源参数 ----------------
case "$MODE" in
    h2)
        PROFILES=""
        info "模式：H2 文件库（零外部依赖）"
        dim  "  数据文件：./data/microtrip.mv.db"
        ;;
    mysql)
        PROFILES="mysql"
        info "模式：MySQL（容器）"
        export DB_HOST="${DB_HOST:-localhost}"
        export DB_PORT="${DB_PORT:-3306}"
        export DB_NAME="${DB_NAME:-microtrip}"
        export DB_USERNAME="${DB_USERNAME:-microtrip}"
        export DB_PASSWORD="${DB_PASSWORD:-microtrip123}"
        dim "  数据源：jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}  as ${DB_USERNAME}"
        ;;
    full)
        PROFILES="mysql,redis"
        info "模式：MySQL + Redis（贴近生产拓扑）"
        export DB_HOST="${DB_HOST:-localhost}"
        export DB_PORT="${DB_PORT:-3306}"
        export DB_NAME="${DB_NAME:-microtrip}"
        export DB_USERNAME="${DB_USERNAME:-microtrip}"
        export DB_PASSWORD="${DB_PASSWORD:-microtrip123}"
        export REDIS_HOST="${REDIS_HOST:-localhost}"
        export REDIS_PORT="${REDIS_PORT:-6379}"
        dim "  数据源：jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}  as ${DB_USERNAME}"
        dim "  Redis ：${REDIS_HOST}:${REDIS_PORT}（令牌吊销跨实例共享）"
        ;;
    -h|--help|help)
        sed -n '1,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        die "未知模式：$MODE（可选 h2 / mysql / full）"
        ;;
esac

# ---------------- 依赖可用性预检 ----------------
if [ "$MODE" != "h2" ]; then
    if ! port_in_use "${DB_PORT:-3306}"; then
        err "MySQL 端口 ${DB_PORT:-3306} 未监听 —— 容器可能没起"
        dim "  先执行：./scripts/infra.sh up"
        exit 1
    fi
    ok "MySQL 端口可达"
fi
if [ "$MODE" = "full" ] && ! port_in_use "${REDIS_PORT:-6379}"; then
    err "Redis 端口 ${REDIS_PORT:-6379} 未监听"
    dim "  先执行：./scripts/infra.sh up"
    exit 1
fi

# ---------------- 端口占用预检 ----------------
if port_in_use "$PORT"; then
    err "端口 $PORT 已被占用，无法启动"
    dim "  换端口：SERVER_PORT=3001 ./scripts/dev.sh $MODE"
    dim "  或找出占用者：netstat -ano | grep $PORT"
    exit 1
fi

# ---------------- 启动 ----------------
title "启动微旅途后端（开发模式）"
dim "  端口：$PORT    Profile：${PROFILES:-<无，使用默认>}"
dim "  接口文档：http://localhost:${PORT}/swagger-ui.html"
dim "  健康检查：http://localhost:${PORT}/actuator/health"
dim "  停止：Ctrl + C"
echo

MVN_ARGS=(-q spring-boot:run)
if [ -n "$PROFILES" ]; then
    MVN_ARGS+=(-Dspring-boot.run.profiles="$PROFILES")
fi
# 把 SERVER_PORT 透传给应用
MVN_ARGS+=(-Dspring-boot.run.arguments="--server.port=$PORT")

exec "$MVN_CMD" "${MVN_ARGS[@]}"
