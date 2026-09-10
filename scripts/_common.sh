#!/usr/bin/env bash
# ============================================================
# 脚本公共库（被 scripts/ 下其他脚本 source）
# ------------------------------------------------------------
# 解决的三个跨平台问题：
#   1) Windows Git Bash 下 mvn 必须用 mvn.cmd —— sh 版 mvn 会把
#      MAVEN_HOME 当 Unix 路径传给 Windows 原生 Java，报「找不到主类 Launcher」
#   2) MAVEN_HOME / JAVA_HOME 在部分终端未导出，这里按常见安装路径兜底
#   3) Git Bash 下终端对 ANSI 支持不一致，非 TTY 时关闭颜色避免日志里出现转义码
# ============================================================

# ---------- 定位项目根目录 ----------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# ---------- 颜色 ----------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
    C_BLUE=$'\033[34m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_OFF=$'\033[0m'
else
    C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''; C_DIM=''; C_BOLD=''; C_OFF=''
fi

info()  { printf '%s\n' "${C_BLUE}==>${C_OFF} $*"; }
ok()    { printf '%s\n' "${C_GREEN} ✓${C_OFF} $*"; }
warn()  { printf '%s\n' "${C_YELLOW} !${C_OFF} $*" >&2; }
err()   { printf '%s\n' "${C_RED} ✗${C_OFF} $*" >&2; }
die()   { err "$*"; exit 1; }
dim()   { printf '%s\n' "${C_DIM}$*${C_OFF}"; }
title() { printf '\n%s\n' "${C_BOLD}$*${C_OFF}"; }

# ---------- 判断是否 Windows（Git Bash / MSYS / Cygwin）----------
is_windows() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

# ---------- Maven 命令解析 ----------
# Git Bash 里若直接调 `mvn`，命中的是 sh 脚本版本，会把 MAVEN_HOME 这类
# Windows 路径交给原生 java，导致「找不到或无法加载主类 Launcher」。
resolve_mvn() {
    if is_windows; then
        for c in mvn.cmd mvn.bat; do
            if command -v "$c" >/dev/null 2>&1; then echo "$c"; return 0; fi
        done
    fi
    if command -v mvn >/dev/null 2>&1; then echo "mvn"; return 0; fi
    return 1
}

# ---------- 路径形态互转（Windows 下 cygpath）----------
to_unix_path() {
    local p="$1"
    if is_windows && printf '%s' "$p" | grep -qE '^[A-Za-z]:'; then
        cygpath -u "$p" 2>/dev/null || printf '%s' "$p"
    else
        printf '%s' "$p"
    fi
}

to_win_path() {
    local p="$1"
    if is_windows && printf '%s' "$p" | grep -qE '^/'; then
        cygpath -w "$p" 2>/dev/null || printf '%s' "$p"
    else
        printf '%s' "$p"
    fi
}

java_home_ok() {
    [ -n "${1:-}" ] || return 1
    [ -x "$(to_unix_path "$1")/bin/java" ] 2>/dev/null
}

# 从 PATH 上的 java 反查真实 JAVA_HOME
# Windows 上 `which java` 命中的是 Oracle javapath 转发器（不是 JDK 根目录），
# 但 java 自己知道家在哪 —— 用 -XshowSettings 问它最可靠。
java_home_from_path() {
    command -v java >/dev/null 2>&1 || return 1
    local home
    home="$(java -XshowSettings:properties -version 2>&1 \
        | sed -n 's/^[[:space:]]*java\.home = //p' | head -1)"
    home="${home%$'\r'}"
    [ -n "$home" ] || return 1
    java_home_ok "$home" || return 1
    printf '%s' "$home"
}

# 在候选目录中挑**版本号最高**的 JDK。
# 关键：不能简单地 `for d in dir/jdk-*; do ... break; done`——
# 字母序下 jdk-18.0.1.1 排在 jdk-21.0.10 前面，会选中低版本，
# 结果 Maven 用 JDK 18 编译 release 21 目标，报「不支持发行版本 21」。
pick_highest_jdk() {
    local candidates=()
    local d base ver
    for d in "$@"; do
        [ -d "$d" ] || continue
        base="$(basename "$d")"
        # 只接受形如 jdk-21.0.10 / jdk21 / java-21-openjdk 的目录，跳过 latest 这类别名
        ver="$(printf '%s' "$base" | sed -n 's/.*[^0-9]\([0-9][0-9.]*\)$/\1/p')"
        [ -n "$ver" ] || ver="$(printf '%s' "$base" | sed -n 's/^\([0-9][0-9.]*\).*/\1/p')"
        [ -n "$ver" ] || continue
        [ -x "$d/bin/java" ] || continue
        candidates+=("$ver|$d")
    done
    [ "${#candidates[@]}" -gt 0 ] || return 1
    # sort -V 做版本序排序（Git Bash 的 GNU sort 支持），取最后一行即最高版本
    printf '%s\n' "${candidates[@]}" | sort -t'|' -k1,1V | tail -1 | cut -d'|' -f2-
}

# ---------- 自动补全 JAVA_HOME / MAVEN_HOME ----------
ensure_java_env() {
    # ---------- Java ----------
    if ! java_home_ok "${JAVA_HOME:-}"; then
        local found=""
        if is_windows; then
            found="$(pick_highest_jdk "/c/Program Files/Java"/jdk* \
                                          "/c/Program Files/Eclipse Adoptium"/* \
                                          "/c/Program Files/Microsoft"/jdk* 2>/dev/null)" || found=""
        else
            found="$(pick_highest_jdk /usr/lib/jvm/* \
                                      /opt/java/* \
                                      /Library/Java/JavaVirtualMachines/*/Contents/Home 2>/dev/null)" || found=""
        fi
        if [ -n "$found" ]; then
            export JAVA_HOME="$found"
        else
            # 兜底：从 PATH 上的 java 反查
            found="$(java_home_from_path || true)"
            [ -n "$found" ] && export JAVA_HOME="$found"
        fi
        # Windows 上 mvn.cmd 需要 Windows 形态的路径
        if is_windows && [ -n "${JAVA_HOME:-}" ]; then
            export JAVA_HOME="$(to_win_path "$JAVA_HOME")"
        fi
    fi

    # ---------- Maven ----------
    if [ -z "${MAVEN_HOME:-}" ] || [ ! -d "$(to_unix_path "$MAVEN_HOME")" ]; then
        local mfound=""
        if is_windows; then
            mfound="$(ls -d "/c/Program Files"/apache-maven-* 2>/dev/null | sort -V | tail -1)"
        else
            mfound="$(ls -d /opt/apache-maven-* /usr/share/maven 2>/dev/null | sort -V | tail -1)"
        fi
        if [ -n "$mfound" ]; then
            if is_windows; then
                export MAVEN_HOME="$(to_win_path "$mfound")"
            else
                export MAVEN_HOME="$mfound"
            fi
        fi
    fi
    if [ -n "${MAVEN_HOME:-}" ]; then
        local mbin
        mbin="$(to_unix_path "$MAVEN_HOME")/bin"
        case ":$PATH:" in
            *":$mbin:"*) ;;
            *) export PATH="$mbin:$PATH" ;;
        esac
    fi
}

# ---------- 强制校验 JDK 版本 ----------
# 目的：把「不支持发行版本 21」这类**根因不明**的 Maven 报错挡在前面。
# 机器上装多个 JDK 时（本机同时有 jdk-18 与 jdk-21），一旦 JAVA_HOME 指错，
# Maven 只会报编译期错误，甚至可能编译通过但字节码版本不符，排查成本高。
require_jdk() {
    local required="${1:-21}"
    local java_bin version
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$(to_unix_path "$JAVA_HOME")/bin/java" ]; then
        java_bin="$(to_unix_path "$JAVA_HOME")/bin/java"
    else
        java_bin="$(command -v java || true)"
    fi
    [ -n "$java_bin" ] || die "未找到 java。安装 JDK $required+：winget install EclipseAdoptium.Temurin.21.JDK"
    version="$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    if [ -z "$version" ] || [ "$version" -lt "$required" ] 2>/dev/null; then
        err "当前 JDK 版本为 ${version:-未知}，本项目要求 ≥ $required"
        dim "  使用的 java：$java_bin"
        dim "  JAVA_HOME  ：${JAVA_HOME:-<未设置>}"
        if is_windows; then
            dim "  本机已安装的 JDK："
            ls -d "/c/Program Files/Java"/jdk* 2>/dev/null | sed 's/^/    /'
            dim "  修复：在当前终端显式指定（脚本已自动挑选最高版本，若仍报错请手工设置）"
            dim "    export JAVA_HOME='/c/Program Files/Java/jdk-21.0.10'"
        fi
        die "JDK 版本不满足要求，已中止"
    fi
    ok "JDK $version（$java_bin）"
}

# ---------- 端口占用探测 ----------
port_in_use() {
    local port="$1"
    if is_windows; then
        netstat -ano 2>/dev/null | grep -qE "[.:]${port}[[:space:]]+.*LISTENING"
    else
        (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null && { exec 3>&-; return 0; } || return 1
    fi
}

# ---------- 读取 .env（若存在）----------
load_dotenv() {
    local f="${1:-.env}"
    if [ -f "$f" ]; then
        # 逐行读，跳过注释与空行；不 eval，避免执行 .env 里的命令
        while IFS= read -r line || [ -n "$line" ]; do
            case "$line" in
                ''|\#*) continue ;;
            esac
            key="${line%%=*}"
            val="${line#*=}"
            case "$key" in
                *[!A-Za-z0-9_]*) continue ;;   # 非法变量名跳过
            esac
            # 已存在的环境变量优先，不覆盖
            if [ -z "$(eval "printf '%s' \"\${${key}:-}\"")" ]; then
                export "$key=$val"
            fi
        done < "$f"
    fi
}

# ---------- 生成随机密钥 ----------
gen_secret() {
    local n="${1:-48}"
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 "$n" | tr -d '\n'
    else
        # 兜底：无 openssl 时用 /dev/urandom
        LC_ALL=C tr -dc 'A-Za-z0-9+/' < /dev/urandom | head -c "$((n * 4 / 3))"
    fi
}

# ---------- 简单 HTTP 请求（优先 curl，回退 wget）----------
# 注意：本机环境注入了 http_proxy，它会把 127.0.0.1 的请求也转发出去并返回 502，
# 于是「服务已启动」会被误判为「服务不可用」。对回环目标一律绕过代理。
http_get() {
    local url="$1" timeout="${2:-5}"
    local noproxy=()
    case "$url" in
        *127.0.0.1*|*localhost*|*"[::1]"*) noproxy=(--noproxy '*') ;;
    esac
    if command -v curl >/dev/null 2>&1; then
        curl -fsS --max-time "$timeout" "${noproxy[@]}" "$url"
    else
        wget -qO- --timeout="$timeout" --no-proxy "$url"
    fi
}
