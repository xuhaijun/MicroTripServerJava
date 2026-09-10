#!/usr/bin/env bash
# ============================================================
# 构建打包
# ------------------------------------------------------------
# 用法：
#   ./scripts/build.sh                # 跑全量测试并打包（推荐）
#   ./scripts/build.sh --skip-tests   # 跳过测试，快速出包
#   ./scripts/build.sh --docker       # 打包后一并构建容器镜像
#   ./scripts/build.sh --skip-tests --docker
#
# 产物：target/micro-trip-server-boot.jar
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

SKIP_TESTS=0
BUILD_DOCKER=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests|-s) SKIP_TESTS=1 ;;
        --docker|-d)     BUILD_DOCKER=1 ;;
        -h|--help)
            sed -n '1,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) die "未知参数：$arg" ;;
    esac
done

ensure_java_env
MVN_CMD="$(resolve_mvn)" || die "未找到 Maven。本机可装：winget install Apache.Maven"
require_jdk 21

title "构建微旅途后端"
dim "  Maven 命令：$MVN_CMD"
dim "  Java：${JAVA_HOME:-<未设置，使用 PATH 中的 java>}"

START_TS=$(date +%s)
if [ "$SKIP_TESTS" -eq 1 ]; then
    warn "已跳过测试（--skip-tests）—— 生产出包请勿跳过"
    "$MVN_CMD" -B clean package -DskipTests
else
    info "执行 clean package（含全量测试）…"
    "$MVN_CMD" -B clean package
fi
END_TS=$(date +%s)

JAR="target/micro-trip-server-boot.jar"
[ -f "$JAR" ] || die "未生成 $JAR，请检查上方构建日志"

JAR_SIZE="$(du -h "$JAR" | cut -f1)"
ok "打包完成，耗时 $((END_TS - START_TS)) 秒"
dim "  产物：$JAR（$JAR_SIZE）"
dim "  启动：java -jar $JAR --spring.profiles.active=mysql"

# ---------------- 可选：容器镜像 ----------------
if [ "$BUILD_DOCKER" -eq 1 ]; then
    command -v docker >/dev/null 2>&1 || die "未安装 Docker，无法构建镜像"
    docker info >/dev/null 2>&1 || die "Docker 守护进程未运行，请先启动 Docker Desktop"
    load_dotenv .env
    IMAGE="${APP_IMAGE:-micro-trip-server:1.0.0}"
    title "构建容器镜像：$IMAGE"
    # --platform 显式声明：ARM Mac 上默认构建 arm64 镜像，推到 x86 服务器会跑不起来
    docker build --platform "${DOCKER_PLATFORM:-linux/amd64}" -t "$IMAGE" .
    ok "镜像构建完成"
    docker images "$IMAGE" --format '  {{.Repository}}:{{.Tag}}  {{.Size}}  ({{.CreatedSince}})'
fi

echo
title "下一步"
dim "  本地以生产参数试跑：./scripts/run-local.sh"
dim "  部署到服务器        ：./scripts/deploy.sh --host <user@server>"
