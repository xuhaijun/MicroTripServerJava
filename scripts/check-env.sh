#!/usr/bin/env bash
# ============================================================
# 环境自检 —— 开始开发/部署前先跑这个
# ------------------------------------------------------------
# 用法：./scripts/check-env.sh
# 作用：逐项检查 JDK / Maven / Docker / 端口 / 配置文件，
#       给出「缺什么、怎么补」的具体结论，避免编译到一半才报错。
# ============================================================
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

REQUIRED_JAVA=21
FAIL=0

# 先补全 JAVA_HOME / MAVEN_HOME 再检查，否则会把「未导出但实际已安装」误报成缺失
ensure_java_env

title "微旅途后端 · 环境自检"
dim  "项目根目录：$PROJECT_ROOT"
dim  "操作系统：$(uname -s) $(uname -m)"

# ---------------- 1. JDK ----------------
title "1. JDK"
# 注意：Maven 用的是 JAVA_HOME 指向的 JDK，而不是 PATH 上的 java。
# 本机同时装了 jdk-18 与 jdk-21，若 JAVA_HOME 指到 18，Maven 会报
# 「不支持发行版本 21」——这种错误信息完全看不出根因，所以这里两个都报。
if command -v java >/dev/null 2>&1; then
    JAVA_VER_RAW="$(java -version 2>&1 | head -1)"
    JAVA_MAJOR="$(printf '%s' "$JAVA_VER_RAW" | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
    if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge "$REQUIRED_JAVA" ] 2>/dev/null; then
        ok "PATH 上的 java：JDK $JAVA_MAJOR（要求 ≥ $REQUIRED_JAVA）"
        dim "  $JAVA_VER_RAW"
        dim "  路径：$(command -v java)"
    else
        err "PATH 上的 java 版本过低：$JAVA_VER_RAW（要求 ≥ $REQUIRED_JAVA）"
        dim "  本机可装：winget install EclipseAdoptium.Temurin.21.JDK"
        FAIL=1
    fi
else
    err "未找到 java 命令"
    dim "  本机可装：winget install EclipseAdoptium.Temurin.21.JDK"
    FAIL=1
fi
if [ -n "${JAVA_HOME:-}" ]; then
    JH_UNIX="$(to_unix_path "$JAVA_HOME")"
    if [ -x "$JH_UNIX/bin/java" ]; then
        JH_MAJOR="$("$JH_UNIX/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
        if [ -n "$JH_MAJOR" ] && [ "$JH_MAJOR" -ge "$REQUIRED_JAVA" ] 2>/dev/null; then
            ok "JAVA_HOME：JDK $JH_MAJOR  → $JAVA_HOME"
        else
            err "JAVA_HOME 指向 JDK $JH_MAJOR（Maven 将用它编译，会报「不支持发行版本 21」）"
            dim "  当前值：$JAVA_HOME"
            dim "  修复：在当前终端执行 export JAVA_HOME='<JDK 21 路径>'"
            FAIL=1
        fi
    else
        err "JAVA_HOME 无效：$JAVA_HOME（$JH_UNIX/bin/java 不存在）"
        FAIL=1
    fi
else
    warn "JAVA_HOME 未设置（脚本已自动挑选最高版本，但建议显式配置）"
fi

# ---------------- 2. Maven ----------------
title "2. Maven"
if MVN_CMD="$(resolve_mvn)"; then
    MVN_VER="$($MVN_CMD -v 2>&1 | head -1)"
    ok "使用命令：$MVN_CMD"
    dim "  $MVN_VER"
    if is_windows; then
        dim "  （Windows/Git Bash 下已自动选用 mvn.cmd，避免 sh 版 mvn 的路径问题）"
    fi
else
    err "未找到 Maven"
    dim "  本机可装：winget install Apache.Maven"
    FAIL=1
fi
if [ -n "${MAVEN_HOME:-}" ]; then ok "MAVEN_HOME=$MAVEN_HOME"; else warn "MAVEN_HOME 未设置"; fi

# 本地仓库是否已有依赖缓存（决定首次构建快慢）
MVN_REPO="${HOME}/.m2/repository"
if [ -d "$MVN_REPO/org/springframework/boot" ]; then
    ok "本地 Maven 仓库已有 Spring Boot 依赖缓存（首次构建会快很多）"
else
    warn "本地 Maven 仓库无 Spring Boot 缓存，首次构建需联网下载（约 3~5 分钟）"
fi

# ---------------- 3. Docker ----------------
title "3. Docker（可选：容器化部署 / MySQL 开发环境）"
if command -v docker >/dev/null 2>&1; then
    ok "Docker $(docker --version 2>/dev/null | sed 's/^Docker version //;s/,.*//')"
    if docker info >/dev/null 2>&1; then
        ok "Docker 守护进程运行中"
        if docker compose version >/dev/null 2>&1; then
            ok "Docker Compose $(docker compose version --short 2>/dev/null)"
        else
            warn "Docker Compose 不可用（容器编排需要它）"
        fi
    else
        warn "Docker 守护进程未运行"
        if is_windows; then
            dim "  启动 Docker Desktop：\"C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe\""
        else
            dim "  启动：sudo systemctl start docker"
        fi
        dim "  （守护进程未起时不检测 Compose 插件，避免误报）"
    fi
else
    warn "未安装 Docker —— 不影响本地开发（默认 H2 零依赖），但容器化部署需要"
    dim "  本机可装：winget install Docker.DockerDesktop"
fi

# ---------------- 4. 端口占用 ----------------
title "4. 端口占用"
for p in "${SERVER_PORT:-3000}" 3306 6379; do
    if port_in_use "$p"; then
        if [ "$p" = "3000" ]; then
            warn "端口 $p 已被占用 —— 启动应用会失败（可用 SERVER_PORT 换端口）"
        else
            dim "  端口 $p 已被占用（通常是已在跑的 MySQL/Redis 容器，正常）"
        fi
    else
        ok "端口 $p 空闲"
    fi
done

# ---------------- 5. 配置文件 ----------------
title "5. 配置文件"
if [ -f .env ]; then
    ok ".env 存在"
    for k in JWT_SECRET DB_PASSWORD; do
        v="$(grep -E "^${k}=" .env 2>/dev/null | head -1 | cut -d= -f2-)"
        if [ -z "$v" ]; then
            warn ".env 中 $k 为空（生产 profile 下 JWT_SECRET 为空会拒绝启动）"
            dim "  生成：openssl rand -base64 48"
        fi
    done
else
    dim "  未找到 .env —— 本地开发不需要；部署前请 cp .env.example .env 并填写"
fi
[ -f docker-compose.yml ]      && ok "docker-compose.yml（开发基础设施）"      || warn "缺少 docker-compose.yml"
[ -f docker-compose.prod.yml ] && ok "docker-compose.prod.yml（生产编排）"      || warn "缺少 docker-compose.prod.yml"
[ -f Dockerfile ]              && ok "Dockerfile"                                || warn "缺少 Dockerfile"

# ---------------- 6. 关键源码结构 ----------------
title "6. 项目结构"
if [ -f pom.xml ] && [ -d src/main/java ]; then
    JAVA_FILES="$(find src/main/java -name '*.java' | wc -l | tr -d ' ')"
    TEST_FILES="$(find src/test/java -name '*.java' 2>/dev/null | wc -l | tr -d ' ')"
    ok "pom.xml 与源码结构完整"
    dim "  主代码 $JAVA_FILES 个 Java 文件，测试 $TEST_FILES 个"
else
    err "pom.xml 或 src/main/java 缺失 —— 请确认在项目根目录执行"
    FAIL=1
fi

# ---------------- 结论 ----------------
title "结论"
if [ "$FAIL" -eq 0 ]; then
    ok "必备环境检查通过，可以开始开发/部署"
    dim "  下一步：./scripts/dev.sh        # 本地零依赖启动（H2）"
    dim "         ./scripts/infra.sh up   # 起 MySQL + Redis 容器"
else
    err "存在必须解决的阻塞项，见上方 ✗ 标记"
    exit 1
fi
