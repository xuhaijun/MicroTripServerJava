#!/usr/bin/env bash
# ============================================================
# 本地生产态运行（跑真实 jar，参数贴近线上）
# ------------------------------------------------------------
# 用法：
#   ./scripts/run-local.sh              # prod profile + H2，验证「生产参数能否起来」
#   ./scripts/run-local.sh mysql        # prod + MySQL（需 infra.sh up）
#   ./scripts/run-local.sh full         # prod + MySQL + Redis（最贴近线上）
#   ./scripts/run-local.sh full --bg    # 后台运行，pid 写入 .run/app.pid
#   ./scripts/run-local.sh --stop       # 停止后台实例
#
# 与 dev.sh 的分工：
#   dev.sh        mvn spring-boot:run，开发期快速重启
#   run-local.sh  成品 jar + prod profile，上服务器前最后一次本地验收
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

JAR="target/micro-trip-server-boot.jar"
RUN_DIR=".run"
PID_FILE="$RUN_DIR/app.pid"
LOG_FILE="$RUN_DIR/app.out"

# ---------------- 停止 ----------------
if [ "${1:-}" = "--stop" ]; then
    if [ -f "$PID_FILE" ]; then
        PID="$(cat "$PID_FILE")"
        if kill -0 "$PID" 2>/dev/null; then
            info "停止进程 $PID…"
            kill "$PID" 2>/dev/null || true
            for i in $(seq 1 15); do
                kill -0 "$PID" 2>/dev/null || break
                sleep 1
            done
            kill -9 "$PID" 2>/dev/null || true
            ok "已停止（优雅停机窗口已尽力等待）"
        else
            warn "pid 文件存在但进程已不在，清理即可"
        fi
        rm -f "$PID_FILE"
    else
        warn "没有正在运行的后台实例（$PID_FILE 不存在）"
    fi
    exit 0
fi

MODE="h2"
BACKGROUND=0
for arg in "$@"; do
    case "$arg" in
        h2|mysql|full) MODE="$arg" ;;
        --bg|--background) BACKGROUND=1 ;;
        -h|--help) sed -n '1,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$arg" ;;
    esac
done

[ -f "$JAR" ] || die "未找到 $JAR。先执行：./scripts/build.sh"
command -v java >/dev/null 2>&1 || die "未找到 java"

load_dotenv .env
PORT="${SERVER_PORT:-3000}"
if port_in_use "$PORT"; then
    die "端口 $PORT 已被占用（若上次后台实例还在，先 ./scripts/run-local.sh --stop）"
fi

# ---------------- JWT 密钥 ----------------
# prod profile 开启了 require-externalized：未注入 JWT_SECRET 会直接拒绝启动，
# 这是刻意的 fail-fast（随机密钥会让多副本互不认账、重启即全员掉线）。
# 本地为了方便，从 .jwt_secret 文件读取；文件不存在就生成一份并落盘，
# 这样多次本地重启之间已签发的令牌依然有效，便于调试。
if [ -z "${JWT_SECRET:-}" ]; then
    if [ -f .jwt_secret ]; then
        JWT_SECRET="$(tr -d '\r\n' < .jwt_secret)"
        dim "  已从 .jwt_secret 读取 JWT 密钥（本地调试用）"
    else
        JWT_SECRET="$(gen_secret 48)"
        printf '%s' "$JWT_SECRET" > .jwt_secret
        warn "已生成新的 JWT 密钥并写入 .jwt_secret（该文件已在 .gitignore 中）"
    fi
    export JWT_SECRET
fi

# ---------------- Profile 与数据源 ----------------
case "$MODE" in
    h2)    PROFILES="prod" ;;
    mysql) PROFILES="prod,mysql" ;;
    full)  PROFILES="prod,mysql,redis" ;;
esac

case "$MODE" in
    mysql|full)
        port_in_use "${DB_PORT:-3306}" || die "MySQL 未监听，先执行 ./scripts/infra.sh up"
        export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-3306}"
        export DB_NAME="${DB_NAME:-microtrip}"
        export DB_USERNAME="${DB_USERNAME:-microtrip}"
        export DB_PASSWORD="${DB_PASSWORD:-microtrip123}"
        if [ "$MODE" = "full" ]; then
            port_in_use "${REDIS_PORT:-6379}" || die "Redis 未监听，先执行 ./scripts/infra.sh up"
            export REDIS_HOST="${REDIS_HOST:-localhost}" REDIS_PORT="${REDIS_PORT:-6379}"
        fi
        ;;
esac

# 本地无 TLS：关掉 HTTPS 强制，否则所有请求会被重定向到不存在的 https 端口
export REQUIRE_HTTPS="${REQUIRE_HTTPS_OVERRIDE:-false}"
# Actuator 指标端点白名单：本地保持默认仅回环，curl 127.0.0.1 正常可访问
export ACTUATOR_ALLOW_LIST="${ACTUATOR_ALLOW_LIST:-127.0.0.1,::1}"

JAVA_ARGS=(-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai)
if [ -n "${JAVA_OPTS:-}" ]; then
    # JAVA_OPTS 是「一串参数」，这里按空白拆成数组再追加，避免整体被当成单个参数
    read -r -a _extra_opts <<< "${JAVA_OPTS}"
    JAVA_ARGS+=("${_extra_opts[@]}")
fi

title "本地生产态启动"
dim "  Profile    ：$PROFILES"
dim "  端口       ：$PORT"
dim "  HTTPS 强制 ：$REQUIRE_HTTPS（本地无证书，已按 false 处理）"
dim "  健康检查   ：http://127.0.0.1:$PORT/actuator/health"
dim "  接口文档   ：http://127.0.0.1:$PORT/swagger-ui.html"
echo

if [ "$BACKGROUND" -eq 1 ]; then
    mkdir -p "$RUN_DIR"
    nohup java "${JAVA_ARGS[@]}" -jar "$JAR" \
        --spring.profiles.active="$PROFILES" --server.port="$PORT" \
        > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    ok "已后台启动，pid=$(cat "$PID_FILE")"
    dim "  日志：$LOG_FILE"
    dim "  停止：./scripts/run-local.sh --stop"
    echo
    info "等待就绪…"
    for i in $(seq 1 40); do
        if http_get "http://127.0.0.1:$PORT/actuator/health" 3 2>/dev/null | grep -q '"status":"UP"'; then
            ok "服务已就绪（约 ${i}s）"
            exit 0
        fi
        sleep 1
    done
    err "40 秒内未就绪，末尾日志："
    tail -30 "$LOG_FILE" >&2
    exit 1
else
    exec java "${JAVA_ARGS[@]}" -jar "$JAR" \
        --spring.profiles.active="$PROFILES" --server.port="$PORT"
fi
