#!/usr/bin/env bash
# ============================================================
# 健康检查 + 接口冒烟测试
# ------------------------------------------------------------
# 用法：
#   ./scripts/health.sh                     # 检查本机 3000 端口
#   ./scripts/health.sh --port 3001
#   ./scripts/health.sh --base https://api.example.com
#   ./scripts/health.sh --quick             # 只探活，不跑业务链路
#
# 冒烟链路（模拟 App 真实调用顺序）：
#   探活 → 注册 → 同步轨迹 → 列表 → 统计 → 详情 → 删除 → 注销
# 任何一步失败都会明确指出是哪一环，并打印响应体，便于定位。
# ============================================================
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

BASE=""
PORT="${SERVER_PORT:-3000}"
QUICK=0
while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --base) BASE="$2"; shift 2 ;;
        --quick) QUICK=1; shift ;;
        -h|--help) sed -n '1,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
[ -z "$BASE" ] && BASE="http://127.0.0.1:$PORT"
API="$BASE/api/v1"

command -v curl >/dev/null 2>&1 || die "需要 curl（Windows 10+ 自带；Linux：apt install curl）"

PASS=0; FAILED=0
step() { printf '\n%s\n' "${C_BOLD}$*${C_OFF}"; }
pass() { ok "$*"; PASS=$((PASS + 1)); }
fail() { err "$*"; FAILED=$((FAILED + 1)); }

# ---------- 轻量 JSON 取值：jq → python → sed 三级回退 ----------
jget() {
    local json="$1" key="$2"
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$json" | jq -r ".$key // empty" 2>/dev/null
    elif command -v python3 >/dev/null 2>&1; then
        printf '%s' "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in '$key'.split('.'):
    if isinstance(d, dict):
        d = d.get(k)
    else:
        d = None
        break
print('' if d is None or isinstance(d, (dict, list)) else d)
" 2>/dev/null
    elif command -v python >/dev/null 2>&1; then
        printf '%s' "$json" | python -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in '$key'.split('.'):
    if isinstance(d, dict):
        d = d.get(k)
    else:
        d = None
        break
print('' if d is None or isinstance(d, (dict, list)) else d)
" 2>/dev/null
    else
        printf '%s' "$json" | sed -n "s/.*\"${key##*.}\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1
    fi
}

# 本机注入的 http_proxy 会把 127.0.0.1 的请求也转发出去，导致拿到代理返回的 502，
# 排查时极易误判为「服务挂了」。对本地目标一律绕过代理。
CURL_NET=()
case "$BASE" in
    *127.0.0.1*|*localhost*) CURL_NET=(--noproxy '*') ;;
esac

# 统一解析 curl 输出：状态码在最后一行，之前是响应体（可能为空）
_run_curl() {
    local raw code resp
    raw="$(curl "$@" 2>/dev/null)"
    code="${raw##*@@HTTP_CODE@@}"
    resp="${raw%$'\n'@@HTTP_CODE@@*}"
    [ "$resp" = "$raw" ] && resp=""     # 未匹配到分隔符说明请求本身失败
    printf '%s\t%s' "${code:-000}" "$resp"
}

# 带状态码的请求：输出「状态码<TAB>响应体」
# 刻意不用临时文件：Windows（尤其 Git Bash）下 curl 落盘与随后的 cat 之间存在
# 可见的时序延迟，会出现「文件还没生成」而误判。改用 -w 把状态码拼在响应末尾再切分。
req() {
    local method="$1" url="$2" body="${3:-}" token="${4:-}"
    local args=(-sS -X "$method" --max-time 30 "${CURL_NET[@]}"
                -w $'\n@@HTTP_CODE@@%{http_code}')
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    _run_curl "${args[@]}" "$url"
}

# 带查询参数的 GET，自动做 URL 编码。
# 必要性：城市名是中文，直接拼进 URL（?city=成都）会被 Tomcat 以
# 「Invalid character found in the request target」拒绝并返回 400 ——
# 看起来像接口挂了，其实是客户端没编码。
req_q() {
    local url="$1"; shift
    local args=(-sS -G --max-time 30 "${CURL_NET[@]}"
                -w $'\n@@HTTP_CODE@@%{http_code}')
    local kv
    for kv in "$@"; do
        args+=(--data-urlencode "$kv")
    done
    _run_curl "${args[@]}" "$url"
}

title "微旅途后端 · 健康检查与冒烟测试"
dim "  目标：$BASE"

# ---------------- 1. 探活 ----------------
step "1. 服务探活"
RESP="$(req GET "$BASE/actuator/health")"
CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
if [ "$CODE" = "200" ] && printf '%s' "$BODY" | grep -q '"status":"UP"'; then
    pass "GET /actuator/health → 200 UP"
else
    fail "GET /actuator/health → $CODE（服务可能未启动）"
    dim "  响应：$BODY"
    err "探活失败即中止后续检查。启动命令：./scripts/run-local.sh full --bg"
    exit 1
fi

RESP="$(req GET "$API/health")"
CODE="${RESP%%$'\t'*}"
[ "$CODE" = "200" ] && pass "GET /api/v1/health → 200" || fail "GET /api/v1/health → $CODE"

if [ "$QUICK" -eq 1 ]; then
    echo; ok "快速模式：仅探活完成"; exit 0
fi

# ---------------- 2. 注册 ----------------
step "2. 账号注册"
PHONE="199$(date +%H%M%S)$(shuf -i 10-99 -n 1 2>/dev/null || printf '%02d' $((RANDOM % 90 + 10)))"
PHONE="${PHONE:0:11}"
RESP="$(req POST "$API/auth/register" "{\"phone\":\"$PHONE\",\"password\":\"Test123456\",\"nickname\":\"冒烟测试\"}")"
CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
TOKEN="$(jget "$BODY" token)"
if { [ "$CODE" = "200" ] || [ "$CODE" = "201" ]; } && [ -n "$TOKEN" ]; then
    pass "POST /api/v1/auth/register → $CODE（测试账号 $PHONE）"
else
    fail "POST /api/v1/auth/register → $CODE"
    dim "  响应：$BODY"
    err "无法获取令牌，中止后续链路测试"
    exit 1
fi

# ---------------- 3. 重复注册应被拒 ----------------
RESP="$(req POST "$API/auth/register" "{\"phone\":\"$PHONE\",\"password\":\"Test123456\",\"nickname\":\"重复\"}")"
CODE="${RESP%%$'\t'*}"
if [ "$CODE" = "409" ] || [ "$CODE" = "400" ]; then
    pass "重复手机号注册被拒 → $CODE"
else
    fail "重复手机号注册 → $CODE（期望 409/400，说明唯一约束未生效）"
fi

# ---------------- 4. 未授权访问应 401 ----------------
RESP="$(req GET "$API/trajectory/list")"
CODE="${RESP%%$'\t'*}"
[ "$CODE" = "401" ] && pass "无令牌访问受保护接口 → 401" || fail "无令牌访问 → $CODE（期望 401）"

# ---------------- 5. 同步轨迹 ----------------
step "3. 轨迹同步（幂等 upsert）"
TR_ID="smoke_$(date +%s)"
NOW_MS="$(( $(date +%s) * 1000 ))"
SYNC_BODY="{\"trajectory\":{\"id\":\"$TR_ID\",\"start\":$NOW_MS,\"end\":$((NOW_MS + 600000)),\"distance\":1234.5,\"duration\":600,\"ascent\":12.5,\"descent\":8.0,\"title\":\"冒烟轨迹\",\"city\":\"成都\",\"pts\":[{\"lat\":30.5728,\"lng\":104.0668,\"ts\":$NOW_MS,\"alt\":500.0,\"spd\":3.2},{\"lat\":30.5730,\"lng\":104.0670,\"ts\":$((NOW_MS + 60000)),\"alt\":505.0,\"spd\":3.5},{\"lat\":30.5735,\"lng\":104.0675,\"ts\":$((NOW_MS + 120000)),\"alt\":510.0,\"spd\":3.1}]}}"
RESP="$(req POST "$API/trajectory/sync" "$SYNC_BODY" "$TOKEN")"
CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
if [ "$CODE" = "200" ] && printf '%s' "$BODY" | grep -q '"ok":true'; then
    pass "POST /api/v1/trajectory/sync → 200（id=$TR_ID，3 个 GPS 点）"
else
    fail "POST /api/v1/trajectory/sync → $CODE"
    dim "  响应：$BODY"
fi

# 幂等性：同 id 再传一次，应仍成功且不产生重复
RESP="$(req POST "$API/trajectory/sync" "$SYNC_BODY" "$TOKEN")"
CODE="${RESP%%$'\t'*}"
[ "$CODE" = "200" ] && pass "同 id 重复同步 → 200（幂等）" || fail "同 id 重复同步 → $CODE"

# ---------------- 6. 列表 / 统计 ----------------
step "4. 查询接口"
RESP="$(req GET "$API/trajectory/list?page=1&pageSize=10" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
if [ "$CODE" = "200" ] && printf '%s' "$BODY" | grep -q '"list"'; then
    TOTAL="$(jget "$BODY" total)"
    pass "GET /trajectory/list → 200（total=$TOTAL）"
    # 关键回归点：列表是摘要投影，不应携带 pts 大字段
    if printf '%s' "$BODY" | grep -q '"pts"'; then
        fail "列表响应中出现了 pts 字段 —— 投影查询已退化为实体查询（性能回归）"
    else
        pass "列表响应不含 pts 大字段（摘要投影生效）"
    fi
else
    fail "GET /trajectory/list → $CODE"
    dim "  响应：$BODY"
fi

RESP="$(req GET "$API/trajectory/stats" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
if [ "$CODE" = "200" ]; then
    pass "GET /trajectory/stats → 200（${BODY:0:120}）"
else
    fail "GET /trajectory/stats → $CODE"
fi

RESP="$(req GET "$API/trajectory/$TR_ID" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
if [ "$CODE" = "200" ] && printf '%s' "$BODY" | grep -q '"pts"'; then
    pass "GET /trajectory/{id} → 200（详情含 pts，符合契约）"
else
    fail "GET /trajectory/{id} → $CODE"
    dim "  响应：${BODY:0:200}"
fi

# ---------------- 7. 静态数据 ----------------
step "5. 静态数据（公开接口）"
# 用 req_q 让中文城市名走 URL 编码（--data-urlencode）
for ep in scenery food; do
    RESP="$(req_q "$API/$ep/list" "city=成都")"
    CODE="${RESP%%$'\t'*}"; BODY="${RESP#*$'\t'}"
    if [ "$CODE" = "200" ]; then
        pass "GET /$ep/list?city=成都 → 200（${#BODY} 字节）"
    else
        fail "GET /$ep/list?city=成都 → $CODE"
        dim "  响应：${BODY:0:160}"
    fi
done

# ---------------- 8. 清理 ----------------
step "6. 清理与注销"
RESP="$(req DELETE "$API/trajectory/$TR_ID" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"
if [ "$CODE" = "200" ] || [ "$CODE" = "204" ]; then
    pass "DELETE /trajectory/{id} → $CODE"
else
    fail "DELETE /trajectory/{id} → $CODE"
fi

RESP="$(req POST "$API/auth/logout" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"
if [ "$CODE" = "200" ]; then
    pass "POST /auth/logout → 200"
else
    fail "POST /auth/logout → $CODE"
fi

# 注销后旧令牌必须立即失效（吊销生效）
RESP="$(req GET "$API/trajectory/list" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"
if [ "$CODE" = "401" ]; then
    pass "登出后旧令牌失效 → 401（吊销生效）"
else
    fail "登出后旧令牌仍可用 → $CODE（吊销未生效，存在安全风险）"
fi

# ---------------- 9. 账号注销（清理测试数据）----------------
RESP="$(req DELETE "$API/auth/account" "" "$TOKEN")"
CODE="${RESP%%$'\t'*}"
dim "  测试账号注销 → $CODE（若已因登出失效属正常）"

# ---------------- 结论 ----------------
echo
title "结论"
printf '  通过 %s%d%s 项，失败 %s%d%s 项\n' "$C_GREEN" "$PASS" "$C_OFF" "$C_RED" "$FAILED" "$C_OFF"
if [ "$FAILED" -eq 0 ]; then
    ok "全部通过，服务状态正常"
    exit 0
else
    err "存在失败项，请按上方逐条排查"
    exit 1
fi
