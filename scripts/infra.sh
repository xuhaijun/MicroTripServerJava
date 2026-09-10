#!/usr/bin/env bash
# ============================================================
# 本地开发基础设施管理（MySQL 8 + Redis 7 容器）
# ------------------------------------------------------------
# 用法：
#   ./scripts/infra.sh up       起容器（首次会自动初始化库与账号）
#   ./scripts/infra.sh status   查看健康状态
#   ./scripts/infra.sh logs     跟踪日志
#   ./scripts/infra.sh down     停止（保留数据卷）
#   ./scripts/infra.sh reset    停止并删除数据卷（⚠️ 数据清空，需 --yes 确认）
#   ./scripts/infra.sh mysql    进入 MySQL 命令行
#   ./scripts/infra.sh redis    进入 Redis 命令行
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

COMPOSE_FILE="docker-compose.yml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")

require_docker() {
    command -v docker >/dev/null 2>&1 || die "未安装 Docker。本机可装：winget install Docker.DockerDesktop"
    docker info >/dev/null 2>&1 || die "Docker 守护进程未运行。请先启动 Docker Desktop 后再试。"
}

load_env() {
    # 允许用户用 .env 覆盖开发用账号密码（没有 .env 时用 compose 内的默认值）
    load_dotenv .env
}

case "${1:-help}" in
    up)
        require_docker
        load_env
        info "启动 MySQL + Redis 容器…"
        "${COMPOSE[@]}" up -d
        echo
        info "等待健康检查通过（MySQL 首次初始化约 20~40 秒）…"
        for i in $(seq 1 40); do
            sleep 3
            MYSQL_STATE="$(docker inspect -f '{{.State.Health.Status}}' microtrip-mysql 2>/dev/null || echo "unknown")"
            REDIS_STATE="$(docker inspect -f '{{.State.Health.Status}}' microtrip-redis 2>/dev/null || echo "unknown")"
            printf '\r  MySQL: %-9s Redis: %-9s [%02d/40]' "$MYSQL_STATE" "$REDIS_STATE" "$i"
            if [ "$MYSQL_STATE" = "healthy" ] && [ "$REDIS_STATE" = "healthy" ]; then
                echo; ok "两个容器均已就绪"
                echo
                dim "  MySQL : 127.0.0.1:3306  库 ${MYSQL_DATABASE:-microtrip}  账号 ${MYSQL_USER:-microtrip}"
                dim "  Redis : 127.0.0.1:6379"
                dim "  应用启动：./scripts/dev.sh mysql"
                exit 0
            fi
        done
        echo
        err "超时未就绪，请查看日志：./scripts/infra.sh logs"
        exit 1
        ;;
    down)
        require_docker
        info "停止容器（数据卷保留）…"
        "${COMPOSE[@]}" down
        ok "已停止。重新启动：./scripts/infra.sh up"
        ;;
    reset)
        require_docker
        if [ "${2:-}" != "--yes" ]; then
            warn "reset 会删除 MySQL / Redis 数据卷，本地开发数据将全部丢失。"
            warn "确认请执行：./scripts/infra.sh reset --yes"
            exit 1
        fi
        info "停止容器并删除数据卷…"
        "${COMPOSE[@]}" down -v
        ok "已重置（下次 up 会重新初始化空库）"
        ;;
    status)
        require_docker
        "${COMPOSE[@]}" ps
        echo
        for c in microtrip-mysql microtrip-redis; do
            if docker inspect "$c" >/dev/null 2>&1; then
                printf '  %-18s %s\n' "$c" "$(docker inspect -f '{{.State.Status}} / health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "$c")"
            fi
        done
        ;;
    logs)
        require_docker
        "${COMPOSE[@]}" logs -f --tail=100
        ;;
    mysql)
        require_docker
        load_env
        info "进入 MySQL 命令行（退出输入 exit）…"
        docker exec -it microtrip-mysql mysql -u"${MYSQL_USER:-microtrip}" -p"${MYSQL_PASSWORD:-microtrip123}" "${MYSQL_DATABASE:-microtrip}"
        ;;
    redis)
        require_docker
        docker exec -it microtrip-redis redis-cli
        ;;
    help|*)
        cat <<'EOF'
本地开发基础设施管理（MySQL 8 + Redis 7）

  ./scripts/infra.sh up       起容器（首次自动初始化）
  ./scripts/infra.sh status   查看状态与健康检查
  ./scripts/infra.sh logs     跟踪容器日志
  ./scripts/infra.sh down     停止（保留数据卷）
  ./scripts/infra.sh reset --yes  停止并删除数据卷（数据清空）
  ./scripts/infra.sh mysql    进入 MySQL 命令行
  ./scripts/infra.sh redis    进入 Redis 命令行

说明：默认值来自 docker-compose.yml（可被 .env 覆盖）：
  MySQL  microtrip / microtrip123  root / root123456  库 microtrip
  Redis  无密码
EOF
        ;;
esac
