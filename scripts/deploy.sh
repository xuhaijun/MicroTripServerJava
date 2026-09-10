#!/usr/bin/env bash
# ============================================================
# 服务器部署（从本机推送到远程主机）
# ------------------------------------------------------------
# 用法：
#   ./scripts/deploy.sh --host root@1.2.3.4                    # jar + systemd 方式
#   ./scripts/deploy.sh --host root@1.2.3.4 --mode docker      # 容器编排方式
#   ./scripts/deploy.sh --host ubuntu@api.example.com --port 2222 --remote-dir /opt/microtrip
#   ./scripts/deploy.sh --host root@1.2.3.4 --dry-run          # 只做校验，不动服务器
#
# 两条部署路线的取舍：
#   jar + systemd —— 适合单机小规模，启动快、日志进 journald、无需镜像仓库
#   docker compose —— 适合需要 MySQL/Redis/Nginx 一并托管，环境一致性最好
#
# 首次部署前，服务器上需准备（见 docs/部署指南.md 第 4 章）：
#   - JDK 21（jar 方式）
#   - microtrip 系统账号、/opt/microtrip、/var/log/microtrip（jar 方式）
#   - Docker + Compose（容器方式）
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

HOST=""
SSH_PORT=22
MODE="jar"
REMOTE_DIR="/opt/microtrip"
SERVICE="micro-trip-server"
DRY_RUN=0
SKIP_BUILD=0

while [ $# -gt 0 ]; do
    case "$1" in
        --host)       HOST="$2"; shift 2 ;;
        --port)       SSH_PORT="$2"; shift 2 ;;
        --mode)       MODE="$2"; shift 2 ;;
        --remote-dir) REMOTE_DIR="$2"; shift 2 ;;
        --service)    SERVICE="$2"; shift 2 ;;
        --dry-run)    DRY_RUN=1; shift ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        -h|--help)    sed -n '1,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done

[ -n "$HOST" ] || die "必须指定 --host（例如 --host root@1.2.3.4）"
case "$MODE" in jar|docker) ;; *) die "--mode 只能是 jar 或 docker" ;; esac

SSH_OPTS=(-o "Port=$SSH_PORT" -o ConnectTimeout=10 -o ServerAliveInterval=30)
SSH=(ssh "${SSH_OPTS[@]}" "$HOST")
SCP=(scp "${SSH_OPTS[@]}")

JAR="target/micro-trip-server-boot.jar"
IMAGE="${APP_IMAGE:-micro-trip-server:1.0.0}"

title "部署微旅途后端"
dim "  目标主机  ：$HOST（SSH 端口 $SSH_PORT）"
dim "  部署方式  ：$MODE"
dim "  远程目录  ：$REMOTE_DIR"
[ "$DRY_RUN" -eq 1 ] && warn "dry-run 模式：只做校验，不会真正改动服务器"

# ---------------- 1. 配置校验 ----------------
title "1. 校验部署配置"
if [ ! -f .env ]; then
    warn "未找到 .env —— 将不会同步密钥文件到服务器"
    dim "  若服务器尚未配置环境变量，请先 cp .env.example .env 并填写，或手工在服务器创建"
else
    # shellcheck disable=SC1091
    load_dotenv .env
    if [ -z "${JWT_SECRET:-}" ]; then
        die ".env 中 JWT_SECRET 为空 —— prod profile 会拒绝启动。生成：openssl rand -base64 48"
    fi
    if [ -z "${MYSQL_PASSWORD:-}${DB_PASSWORD:-}" ]; then
        warn ".env 中数据库密码为空 —— 生产环境请务必设置"
    fi
    ok ".env 校验通过（JWT_SECRET 已设置）"
fi

# ---------------- 2. SSH 连通性 ----------------
title "2. 检查 SSH 连通性"
if [ "$DRY_RUN" -eq 1 ]; then
    dim "  dry-run：跳过实际连接测试"
else
    if "${SSH[@]}" 'echo ok' >/dev/null 2>&1; then
        ok "SSH 连接成功"
        REMOTE_OS="$("${SSH[@]}" 'cat /etc/os-release 2>/dev/null | head -2 | tail -1 || uname -s' 2>/dev/null | tr -d '\r')"
        dim "  远端系统：$REMOTE_OS"
        if [ "$MODE" = "jar" ]; then
            REMOTE_JAVA="$("${SSH[@]}" 'java -version 2>&1 | head -1' 2>/dev/null | tr -d '\r')"
            if printf '%s' "$REMOTE_JAVA" | grep -qE '"(2[1-9]|[3-9][0-9])'; then
                ok "远端 JDK：$REMOTE_JAVA"
            else
                die "远端未找到 JDK 21+（当前：${REMOTE_JAVA:-未安装}）。安装见 docs/部署指南.md 第 4.1 节"
            fi
        else
            if "${SSH[@]}" 'docker compose version >/dev/null 2>&1'; then
                ok "远端 Docker Compose 可用"
            else
                die "远端 Docker Compose 不可用。安装见 docs/部署指南.md 第 4.2 节"
            fi
        fi
    else
        die "SSH 连接失败。请确认：① 主机地址与端口正确 ② 已配置免密登录（ssh-copy-id）"
    fi
fi

# ---------------- 3. 构建产物 ----------------
title "3. 准备构建产物"
if [ "$MODE" = "jar" ]; then
    if [ "$SKIP_BUILD" -eq 0 ]; then
        info "执行 Maven 打包…"
        ensure_java_env
        MVN_CMD="$(resolve_mvn)" || die "未找到 Maven"
        "$MVN_CMD" -B -q clean package -DskipTests
    fi
    [ -f "$JAR" ] || die "未找到 $JAR（去掉 --skip-build 重新执行）"
    ok "jar 就绪：$JAR（$(du -h "$JAR" | cut -f1)）"
else
    command -v docker >/dev/null 2>&1 || die "本机未安装 Docker，无法构建镜像"
    docker info >/dev/null 2>&1 || die "本机 Docker 守护进程未运行"
    info "构建镜像 $IMAGE…"
    docker build --platform "${DOCKER_PLATFORM:-linux/amd64}" -t "$IMAGE" .
    ok "镜像构建完成"
fi

if [ "$DRY_RUN" -eq 1 ]; then
    echo
    title "dry-run 完成"
    dim "  已校验：.env 密钥、SSH 连通性、远端运行时、构建产物"
    dim "  去掉 --dry-run 即可真正部署"
    exit 0
fi

# ---------------- 4. 推送并重启 ----------------
if [ "$MODE" = "jar" ]; then
    title "4. 推送 jar 并重启服务"

    info "创建远程目录…"
    "${SSH[@]}" "sudo mkdir -p '$REMOTE_DIR' /var/log/microtrip /etc/microtrip && sudo chown -R microtrip:microtrip '$REMOTE_DIR' /var/log/microtrip"

    # 先传成临时文件再原子替换：避免传输中断留下半个 jar 导致服务起不来
    info "上传 jar…"
    "${SCP[@]}" "$JAR" "$HOST:/tmp/app.jar.new"
    "${SSH[@]}" "sudo mv /tmp/app.jar.new '$REMOTE_DIR/app.jar' && sudo chown microtrip:microtrip '$REMOTE_DIR/app.jar'"

    info "同步 systemd 单元…"
    "${SCP[@]}" deploy/systemd/micro-trip-server.service "$HOST:/tmp/micro-trip-server.service"
    "${SSH[@]}" "sudo mv /tmp/micro-trip-server.service /etc/systemd/system/$SERVICE.service && sudo chmod 644 /etc/systemd/system/$SERVICE.service"

    if [ ! -f .env ]; then
        warn "跳过环境变量同步（本机无 .env）"
    else
        # 密钥文件单独处理：权限必须 600 且属主 root，否则同机其他账号可读
        info "同步环境变量文件…"
        "${SCP[@]}" deploy/systemd/micro-trip-server.env.example "$HOST:/tmp/server.env.tpl"
        "${SSH[@]}" "sudo cp /tmp/server.env.tpl /etc/microtrip/server.env.tpl && sudo rm -f /tmp/server.env.tpl"
        warn "环境变量模板已放至 /etc/microtrip/server.env.tpl"
        warn "请在服务器上手工合并真实密钥到 /etc/microtrip/server.env（chmod 600），本脚本刻意不上传 .env"
    fi

    info "重载并重启服务…"
    "${SSH[@]}" "sudo systemctl daemon-reload && sudo systemctl enable $SERVICE >/dev/null 2>&1; sudo systemctl restart $SERVICE"
    sleep 3
    "${SSH[@]}" "systemctl is-active --quiet $SERVICE" \
        && ok "服务已启动" \
        || { err "服务未能保持运行，最近日志："; "${SSH[@]}" "sudo journalctl -u $SERVICE -n 40 --no-pager"; exit 1; }

else
    title "4. 推送镜像并重启容器"
    info "导出镜像…"
    TMP_TAR="target/micro-trip-server-image.tar"
    docker save "$IMAGE" -o "$TMP_TAR"
    ok "镜像已导出（$(du -h "$TMP_TAR" | cut -f1)）"

    info "上传镜像与编排文件…"
    "${SSH[@]}" "mkdir -p '$REMOTE_DIR/deploy/nginx' '$REMOTE_DIR/deploy/certs' '$REMOTE_DIR/logs-app' '$REMOTE_DIR/logs-nginx'"
    "${SCP[@]}" "$TMP_TAR" "$HOST:$REMOTE_DIR/image.tar"
    "${SCP[@]}" docker-compose.prod.yml Dockerfile .env.example "$HOST:$REMOTE_DIR/"
    "${SCP[@]}" deploy/nginx/micro-trip-server.conf "$HOST:$REMOTE_DIR/deploy/nginx/"

    info "加载镜像并启动编排…"
    "${SSH[@]}" "cd '$REMOTE_DIR' && docker load -i image.tar && rm -f image.tar"
    warn "服务器上的 $REMOTE_DIR/.env 需包含真实密钥（本脚本上传的是 .env.example 模板，勿直接改名使用）"
    "${SSH[@]}" "cd '$REMOTE_DIR' && docker compose -f docker-compose.prod.yml up -d --no-build"
    rm -f "$TMP_TAR"
    ok "容器编排已启动"
fi

# ---------------- 5. 部署后验证 ----------------
title "5. 部署后验证"
if [ "$MODE" = "docker" ]; then
    "${SSH[@]}" "cd '$REMOTE_DIR' && docker compose -f docker-compose.prod.yml ps"
else
    "${SSH[@]}" "systemctl status $SERVICE --no-pager -l | head -12"
fi

echo
ok "部署完成"
dim "  远程日志："
if [ "$MODE" = "docker" ]; then
    dim "    ssh $HOST \"cd $REMOTE_DIR && docker compose -f docker-compose.prod.yml logs -f app\""
else
    dim "    ssh $HOST 'sudo journalctl -u $SERVICE -f'"
fi
dim "  冒烟测试："
dim "    ./scripts/health.sh --base https://<你的域名>"
