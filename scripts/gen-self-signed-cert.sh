#!/usr/bin/env bash
# ============================================================
# 自签 TLS 证书（仅用于本地/内网联调 HTTPS）
# ------------------------------------------------------------
# 用法：
#   ./scripts/gen-self-signed-cert.sh                  # 默认域名 localhost
#   ./scripts/gen-self-signed-cert.sh api.example.com  # 指定域名/IP
#
# 产物（写入 deploy/certs/，已被 .gitignore 忽略，切勿提交）：
#   privkey.pem    私钥
#   fullchain.pem  证书（自签时即证书本身）
#
# ⚠️ 自签证书不会被浏览器/客户端信任，仅用于打通 HTTPS 链路做联调。
#    生产必须使用正式证书，签发方式见 docs/部署指南.md 第 4.4 节：
#      - Let's Encrypt（certbot，免费，需公网域名）
#      - 云厂商证书（阿里云/腾讯云免费 DV 证书，可下载 Nginx 格式）
#
# 注意：Android 客户端默认不信任自签证书，App 侧需额外配置信任或改用正式证书。
# ============================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

DOMAIN="${1:-localhost}"
CERT_DIR="deploy/certs"
DAYS="${CERT_DAYS:-825}"

command -v openssl >/dev/null 2>&1 || die "需要 openssl（Git Bash 自带；Linux：apt install openssl）"

mkdir -p "$CERT_DIR"

if [ -f "$CERT_DIR/fullchain.pem" ] || [ -f "$CERT_DIR/privkey.pem" ]; then
    warn "检测到已有证书文件，继续将覆盖"
    printf '  确认覆盖请输入 yes：'
    read -r ans
    [ "$ans" = "yes" ] || { info "已取消"; exit 0; }
fi

title "生成自签证书"
dim "  域名：$DOMAIN"
dim "  有效期：$DAYS 天"
dim "  输出：$CERT_DIR/"

# SAN 必须显式声明：现代客户端（含 Android 7+、Chrome）已忽略 CN，
# 只看 subjectAltName；只填 CN 会报「证书与域名不匹配」。
cat > "$CERT_DIR/openssl.cnf" <<EOF
[req]
default_bits       = 2048
prompt             = no
default_md         = sha256
distinguished_name = dn
x509_extensions    = v3_req

[dn]
C  = CN
ST = Sichuan
L  = Chengdu
O  = MicroTrip
CN = $DOMAIN

[v3_req]
subjectAltName   = @alt_names
basicConstraints = critical, CA:FALSE
keyUsage         = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = $DOMAIN
$(printf 'DNS.2 = www.%s' "$DOMAIN")
DNS.3 = localhost
IP.1  = 127.0.0.1
EOF

openssl req -x509 -nodes -newkey rsa:2048 \
    -keyout "$CERT_DIR/privkey.pem" \
    -out "$CERT_DIR/fullchain.pem" \
    -days "$DAYS" \
    -config "$CERT_DIR/openssl.cnf" 2>/dev/null

# 权限收紧：私钥不应被同机其他用户读取
chmod 600 "$CERT_DIR/privkey.pem"
chmod 644 "$CERT_DIR/fullchain.pem"

ok "证书生成完成"
openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -subject -dates -ext subjectAltName 2>/dev/null | sed 's/^/  /'

echo
title "使用方式"
dim "  容器方式：docker compose -f docker-compose.prod.yml up -d"
dim "            （证书已挂载到 Nginx 的 /etc/nginx/certs/）"
dim "  访问    ：https://$DOMAIN  （浏览器会提示证书不受信任，属正常）"
dim "  验证    ：curl -k https://127.0.0.1/actuator/health"
echo
warn "生产请勿使用自签证书 —— 见本脚本头部说明"
