#!/usr/bin/env bash
# ============================================================
# 一键启动全家桶：MySQL + Redis + MicroTripServer（后台常驻）
# ------------------------------------------------------------
# 用法：
#   ./scripts/start-all.sh              # full 模式：MySQL + Redis + App
#   ./scripts/start-all.sh mysql        # MySQL + App（不启用 Redis）
#   ./scripts/start-all.sh h2           # 仅 App（H2 文件库，零依赖）
#   ./scripts/start-all.sh full --build # 先重新打包再启动
#   ./scripts/start-all.sh --stop      # 停止 App（等同 ./scripts/run-local.sh --stop）
#
# 依赖自动拉起：
#   - MySQL：已监听则跳过；否则优先 net start MySQL84 服务，失败则命令行后台 mysqld
#   - Redis：已监听则跳过；否则 net start Redis（本机已是 Windows 服务）
# App 始终后台运行（run-local.sh ... --bg），进程独立于本脚本。
#
# ⚠️ 必须在你自己的终端（Git Bash）运行！AI 沙箱的后台进程会被回收，
#    无法替你维持常驻服务。
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

MODE="full"
DO_BUILD=0
for arg in "$@"; do
    case "$arg" in
        h2|mysql|full) MODE="$arg" ;;
        --build|-b)    DO_BUILD=1 ;;
        --stop)        exec "$(dirname "${BASH_SOURCE[0]}")/run-local.sh" --stop ;;
        -h|--help)     sed -n '1,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$arg（用法见 --help）" ;;
    esac
done

title "一键启动 MicroTripServerJava（$MODE 模式）"
NEED_MYSQL=0; NEED_REDIS=0
case "$MODE" in
    mysql|full) NEED_MYSQL=1 ;;
esac
[ "$MODE" = "full" ] && NEED_REDIS=1

MYSQL_PORT=3306
REDIS_PORT=6379

# ---------------- 1. MySQL ----------------
if [ "$NEED_MYSQL" -eq 1 ]; then
    if port_in_use "$MYSQL_PORT"; then
        ok "MySQL 已在监听 :$MYSQL_PORT（跳过启动）"
    else
        info "MySQL 未运行，尝试拉起…"
        if net start MySQL84 >/dev/null 2>&1; then
            ok "已通过 Windows 服务 MySQL84 启动"
        else
            warn "服务 MySQL84 不可用（未注册或数据目录不匹配）"
            info "改用命令行后台启动 mysqld（数据目录 D:/mysql-local/data）…"
            MYSQLD="$(resolve_cli mysqld || echo '/c/Program Files/MySQL/MySQL Server 8.4/bin/mysqld.exe')"
            nohup "$MYSQLD" --datadir=D:/mysql-local/data --port=3306 \
                --character-set-server=utf8mb4 --log-error=D:/mysql-local/mysql_local.err \
                >/dev/null 2>&1 &
        fi
        for i in $(seq 1 30); do port_in_use "$MYSQL_PORT" && break; sleep 1; done
        port_in_use "$MYSQL_PORT" && ok "MySQL 端口就绪" \
            || die "MySQL 启动超时，请手动处理（参考 docs/中间件运维速查.md）"
    fi
    # 连通性确认（microtrip 库）
    MYSQL_CLI="$(resolve_cli mysql || echo '/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe')"
    if "$MYSQL_CLI" -h127.0.0.1 -P3306 -umicrotrip -pmicrotrip123 -e "SELECT 1;" >/dev/null 2>&1; then
        ok "microtrip 库可连接（microtrip / microtrip123）"
    else
        warn "MySQL 已起来但 microtrip 库连不上（可能是空库或数据目录不对）"
        dim "  · 库不存在 → 先跑：./scripts/db-init.sh"
        dim "  · 数据目录指向默认空库 → 以管理员运行 scripts/install-mysql-service.bat"
    fi
fi

# ---------------- 2. Redis ----------------
if [ "$NEED_REDIS" -eq 1 ]; then
    if port_in_use "$REDIS_PORT"; then
        ok "Redis 已在监听 :$REDIS_PORT（跳过启动）"
    else
        info "Redis 未运行，尝试 net start Redis…"
        if net start Redis >/dev/null 2>&1; then
            ok "Redis 已启动"
        else
            warn "Redis 服务启动失败（本机应是 Windows 服务），请手动检查"
        fi
    fi
fi

# ---------------- 3. 构建（按需） ----------------
if [ "$DO_BUILD" -eq 1 ] || [ ! -f target/micro-trip-server-boot.jar ]; then
    info "构建 jar（首次启动或显式 --build）…"
    ./scripts/build.sh --skip-tests
fi

# ---------------- 4. 启动 App（后台） ----------------
info "启动 App：run-local.sh $MODE --bg（后台常驻，等待健康检查）…"
"$(dirname "${BASH_SOURCE[0]}")/run-local.sh" "$MODE" --bg
