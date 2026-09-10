# MySQL 初始化脚本目录

`docker-compose.prod.yml` 把本目录挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d`。

**执行时机**：仅在数据卷**首次创建**时执行（即 `microtrip-mysql-data-prod` 为空时）。
之后的 `up` 不会重复执行 —— 想重跑必须先 `docker compose -f docker-compose.prod.yml down -v`
清空数据卷（⚠️ 数据全丢）。

**可以放什么**：任意 `.sql` / `.sh` 文件，按文件名字典序执行。
适合放建库字符集设定、只读账号授权、初始字典数据等。

**注意**：`MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` 已由容器自动创建库与账号，
本目录只需补充额外内容，不要重复建库。

示例（`01-readonly.sql`）：

```sql
-- 给监控/报表用的只读账号，避免把业务账号密码散落到更多地方
CREATE USER IF NOT EXISTS 'microtrip_ro'@'%' IDENTIFIED BY '换成强密码';
GRANT SELECT ON microtrip.* TO 'microtrip_ro'@'%';
FLUSH PRIVILEGES;
```
