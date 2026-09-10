# TLS 证书目录

`docker-compose.prod.yml` 把本目录挂载到 Nginx 容器的 `/etc/nginx/certs`。

## 必须放这两个文件

| 文件名 | 内容 |
|--------|------|
| `fullchain.pem` | 服务器证书 + 中间 CA 链（**顺序：先服务器证书，再中间证书**，顺序错了部分客户端会握手失败） |
| `privkey.pem` | 私钥（模式 600） |

**⚠️ 目录为空时 Nginx 容器会直接启动失败**（`cannot load certificate`），
且因为 `depends_on: app` 已经拉起应用，会出现「应用在跑但网站打不开」的现象。

## 从哪里来

- **正式环境（推荐）**：Let's Encrypt 免费签发，或云厂商免费 DV 证书。
  完整步骤见 [docs/部署指南.md](../../docs/部署指南.md) 第 6.4 节。
- **本地/内网联调**：自签
  ```bash
  ./scripts/gen-self-signed-cert.sh api.example.com
  ```
  自签证书不被 Android 客户端信任，只能验证链路连通性，**不能作为上线方案**。

## 安全提醒

本目录下的 `*.pem` / `*.key` 已被 `.gitignore` 与 `.dockerignore` 排除，
**不要**为了「方便部署」把它们提交进仓库 —— 私钥一旦进过 Git 历史，
即使后续删除也能从历史里捞出来，等同于长期泄露。
