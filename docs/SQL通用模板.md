# SQL 通用操作模板

> 面向日常开发的 SQL 模板速查：单表增删改查 → 多表关联 → 聚合分析 → 事务 → 索引与优化。
> 所有示例基于本项目三张表（结构见 §0），但写法本身是**通用的**，换表名即可用到任何项目。

---

## 0. 示例表结构速览

| 表 | 主键 | 关键列 | 索引 |
|---|---|---|---|
| `users` | `id` varchar(64) | `phone`(唯一)、`nickname`、`role`、`created_at` | `uk_users_phone` |
| `trajectories` | `id` varchar(64) | `user_id`、`start_time`/`end_time`(毫秒)、`distance`(米)、`duration`(秒)、`points_json` | `idx_trajectories_user_start(user_id, start_time DESC)` |
| `trajectory_points` | `id` bigint 自增 | `trajectory_id`、`seq`(点序号)、`ts`、`lat`/`lng`、`spd`/`alt` | `idx_tp_traj_seq`、`idx_tp_traj_latlng` |

> 注意两张表的时间都是 **bigint 毫秒时间戳**，不是 DATETIME。通用模板中两者写法都给出。

---

## 1. 查询 SELECT

### 1.1 基础查询模板

```sql
-- ① 全字段查一行（主键点查，最快）
SELECT * FROM users WHERE id = 'u_000001';

-- ② 只取需要的列（永远优于 SELECT *，尤其是有 text 大字段时）
SELECT id, phone, nickname FROM users WHERE role = 'USER';

-- ③ 条件组合：AND / OR / IN / BETWEEN（记得加括号明确优先级）
SELECT id, title, distance
FROM trajectories
WHERE user_id = 'u_000001'
  AND (city = '成都' OR city = '重庆')
  AND start_time BETWEEN 1756000000000 AND 1757000000000;

-- ④ 模糊查询：前缀匹配能走索引，两边 % 不能
SELECT * FROM users WHERE phone LIKE '139%';        -- ✓ 可走 uk_users_phone
SELECT * FROM users WHERE phone LIKE '%0001';       -- ✗ 全表扫描，数据量大时禁用

-- ⑤ NULL 判断（必须用 IS NULL，= NULL 永远为假）
SELECT id, note FROM trajectories WHERE note IS NULL;

-- ⑥ 去重 + 计数
SELECT COUNT(DISTINCT user_id) AS 活跃用户数 FROM trajectories;

-- ⑦ 排序 + 分页（bigint 毫秒时间戳直接比大小）
SELECT id, title, start_time
FROM trajectories
WHERE user_id = 'u_000001'
ORDER BY start_time DESC
LIMIT 20 OFFSET 0;      -- MySQL 8 也可写 LIMIT 20（省略 offset=0）
```

### 1.2 深分页优化（LIMIT 100000, 20 慢的根因与解法）

`LIMIT 900000, 20` 要先扫过前 90 万行再丢弃，页越深越慢。两种通用解法：

```sql
-- 解法 A【游标分页】：记住上一页最后一行的排序值，下一页从它之后取
-- 前提：排序列有索引且唯一性尽量高（本项目 user_id+start_time 联合索引正好覆盖）
SELECT id, title, start_time
FROM trajectories
WHERE user_id = 'u_000001'
  AND start_time < 1756900000000        -- ← 上一页最后的 start_time（游标）
ORDER BY start_time DESC
LIMIT 20;
-- Flutter/前端翻页时只带"游标"不带页码，天然支持无限下拉列表

-- 解法 B【延迟关联】：先用覆盖索引找出目标行的主键，再回表取整行
SELECT t.*
FROM trajectories t
JOIN (SELECT id FROM trajectories
      ORDER BY start_time DESC
      LIMIT 900000, 20) tmp ON t.id = tmp.id;   -- 子查询只扫索引不回表，快一个量级
```

---

## 2. 插入 INSERT

### 2.1 单条与批量

```sql
-- 单条
INSERT INTO users (id, phone, nickname, password, role, created_at)
VALUES ('u_000001', '13900000001', '小明', 'xxxx', 'USER', UNIX_TIMESTAMP() * 1000);

-- 批量（一条语句多组 VALUES，比循环单条快 5~10 倍；一次 500~1000 行为宜）
INSERT INTO users (id, phone, nickname, password, role, created_at) VALUES
('u_000002', '13900000002', '小红', 'xxxx', 'USER', UNIX_TIMESTAMP() * 1000),
('u_000003', '13900000003', '小刚', 'xxxx', 'USER', UNIX_TIMESTAMP() * 1000);
```

### 2.2 冲突处理三件套（幂等写入的关键）

```sql
-- ① INSERT IGNORE：唯一键冲突时跳过（不报错、不更新）
INSERT IGNORE INTO users (id, phone, ...) VALUES (...);

-- ② ON DUPLICATE KEY UPDATE【upsert 模板】：存在则更新，不存在则插入
INSERT INTO users (id, phone, nickname, created_at)
VALUES ('u_000001', '13900000001', '新昵称', UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);   -- MySQL 8.0.20+ 建议 VALUES(nickname) 改写为别名写法（见下方提示）

-- ③ REPLACE INTO：冲突时整行删除重插（会触发删除副作用，慎用）
REPLACE INTO users (id, phone, ...) VALUES (...);
```

> **通用性提示**：`VALUES(col)` 写法在 MySQL 8.0.20 起被标记废弃，新写法为
> `INSERT INTO t (...) VALUES (...) AS new ON DUPLICATE KEY UPDATE nickname = new.nickname;`
> 标准语法则是 `MERGE INTO`（Oracle/PostgreSQL 15+ 也支持 `ON CONFLICT`）。换库时注意方言差异。

---

## 3. 更新 UPDATE / 删除 DELETE（高危，先读铁律）

> **铁律**：
> 1. 任何 UPDATE/DELETE **先写同条件的 SELECT** 看一眼影响面，再原样替换关键字执行；
> 2. 批量操作**必须带 WHERE**（工具连接默认开 safe update 模式可兜底）；
> 3. 大表删除**分批**：一次删 1 万行循环执行，避免长事务锁表与主从延迟；
> 4. 先备份（`./scripts/backup.sh --local`），再动手。

```sql
-- 模板：安全更新三步
-- 第 1 步：确认影响面（只读）
SELECT id, note FROM trajectories WHERE user_id = 'u_000001' AND note IS NULL;
-- 第 2 步：事务包裹更新
START TRANSACTION;
UPDATE trajectories SET note = '无备注' WHERE user_id = 'u_000001' AND note IS NULL;
-- 第 3 步：核对 ROW_COUNT() 与第 1 步行数一致 → COMMIT，否则 ROLLBACK
COMMIT;

-- 基于其他表的值更新（JOIN UPDATE 模板）
UPDATE trajectories t
JOIN users u ON t.user_id = u.id
SET t.title = CONCAT(u.nickname, '的轨迹')
WHERE t.title IS NULL;

-- 批量分批删除模板（循环直到无行可删；中间可随时停）
DELETE FROM trajectory_points WHERE trajectory_id = 't_del_001' LIMIT 10000;

-- 软删除思路（推荐：能不物理删就不物理删，加 deleted_at 列 + 查询过滤）
-- UPDATE users SET deleted_at = UNIX_TIMESTAMP()*1000 WHERE id = 'u_000001';
```

---

## 4. 多表关联 JOIN

### 4.1 四种 JOIN 速记

| 类型 | 效果 | 口诀 |
|---|---|---|
| `INNER JOIN` | 两边都匹配才出 | 有则出，无则丢 |
| `LEFT JOIN` | 左表全保留，右边匹配不上填 NULL | 左全右可空 |
| `RIGHT JOIN` | 右表全保留（实际少用，交换表改 LEFT） | 反着来的 LEFT |
| `CROSS JOIN` | 笛卡尔积（忘了 WHERE 就是事故） | 谨慎使用 |

### 4.2 常用模板

```sql
-- ① 一对多统计：每个用户的轨迹数、总里程（聚合 + JOIN）
SELECT u.id, u.nickname,
       COUNT(t.id)          AS 轨迹数,
       ROUND(IFNULL(SUM(t.distance), 0) / 1000, 1) AS 总里程km
FROM users u
LEFT JOIN trajectories t ON t.user_id = u.id
GROUP BY u.id, u.nickname
ORDER BY 总里程km DESC;

-- ② 反向排查：没有轨迹的用户（LEFT JOIN ... IS NULL 经典套路）
SELECT u.id, u.phone
FROM users u
LEFT JOIN trajectories t ON t.user_id = u.id
WHERE t.id IS NULL;

-- 等价 EXISTS 写法（大表时通常更快，命中即返回不扫全量）
SELECT id, phone FROM users u
WHERE NOT EXISTS (SELECT 1 FROM trajectories t WHERE t.user_id = u.id);

-- ③ 三表串联：轨迹 → 点表，取每条轨迹第 1 个点（起点）
SELECT t.id, t.title, p.lat, p.lng, p.ts
FROM trajectories t
JOIN trajectory_points p ON p.trajectory_id = t.id AND p.seq = 1
WHERE t.user_id = 'u_000001';

-- ④ 子查询做条件（标量）
SELECT id, title FROM trajectories
WHERE distance > (SELECT AVG(distance) FROM trajectories);
```

### 4.3 子查询 vs JOIN 怎么选

- 过滤条件"存在/不存在" → 优先 `EXISTS / NOT EXISTS`；
- 取"每组的某个极值行" → 优先窗口函数（见 §5.2）；
- 结果集小（几百行内）且只需一个值 → 标量子查询可读性最好；
- 大表大表关联 → JOIN + 驱动表走索引，EXPLAIN 确认（见 §8）。

---

## 5. 聚合与窗口函数

### 5.1 GROUP BY / HAVING 模板

```sql
-- 按城市统计（GROUP BY 里写不了别名时用表达式原样写一遍；MySQL 允许别名，其他库未必）
SELECT city,
       COUNT(*)          AS 轨迹数,
       ROUND(AVG(distance)/1000, 2) AS 平均km,
       MAX(distance)     AS 最长m
FROM trajectories
WHERE start_time >= 1756000000000
GROUP BY city
HAVING COUNT(*) >= 2          -- HAVING 过滤分组后的结果（WHERE 过滤原始行）
ORDER BY 轨迹数 DESC;
```

### 5.2 窗口函数（MySQL 8+；分组排名 / 去重 / TopN 全靠它）

```sql
-- ① ROW_NUMBER：组内编号 → 本项目 03 去重脚本的核心写法
SELECT id, user_id, start_time,
       ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY synced_at) AS rn
FROM trajectories;

-- ② 每组 TopN：每个用户最近 2 条轨迹
SELECT * FROM (
    SELECT t.*, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY start_time DESC) AS rn
    FROM trajectories t
) x WHERE x.rn <= 2;

-- ③ 累计值：按时间累计里程（运动类 App 周报/月报常用）
SELECT start_time, distance,
       SUM(distance) OVER (ORDER BY start_time) AS 累计里程m
FROM trajectories
WHERE user_id = 'u_000001'
ORDER BY start_time;

-- ④ LAG：与上一条轨迹的间隔天数
SELECT id, start_time,
       DATEDIFF(FROM_UNIXTIME(start_time/1000),
                FROM_UNIXTIME(LAG(start_time) OVER (ORDER BY start_time)/1000)) AS 距上次天数
FROM trajectories WHERE user_id = 'u_000001';
```

---

## 6. 事务

```sql
-- 标准三段式：先开、再改、核对后提交
START TRANSACTION;
DELETE p FROM trajectory_points p
  JOIN trajectories t ON p.trajectory_id = t.id
  WHERE t.id = 't_del_001';
DELETE FROM trajectories WHERE id = 't_del_001';
SELECT ROW_COUNT() AS 删除行数;    -- 与预期不符 → ROLLBACK
COMMIT;

-- 常用开关
SELECT @@autocommit;               -- 查看自动提交（1=每条语句自动提交）
SET autocommit = 0;                -- 会话级关掉，之后必须手动 COMMIT/ROLLBACK
SELECT @@transaction_isolation;    -- 当前隔离级别（默认 REPEATABLE-READ）
```

> **长事务三宗罪**：锁等待、undo 膨胀、主从延迟。原则：事务里只放"必须一起成功/失败"的语句，
> 不要把查询、sleep、业务计算混进事务。

---

## 7. 索引设计与维护

### 7.1 建索引模板与原则

```sql
-- 语法
CREATE INDEX idx_traj_city ON trajectories (city);                 -- 普通索引
ALTER TABLE users ADD INDEX idx_users_created (created_at);        -- 等价写法
CREATE UNIQUE INDEX uk_xxx ON 表 (列);                              -- 唯一索引（兼做业务约束）

-- 联合索引：把"最常等值过滤的列"放最左，范围列放最后
-- 本项目 idx_trajectories_user_start(user_id, start_time DESC) 支持：
--   WHERE user_id = ?                       ✓ 整个索引用上
--   WHERE user_id = ? ORDER BY start_time   ✓ 过滤+排序都不回表排序
--   WHERE start_time > ? （没有 user_id）    ✗ 最左前缀缺失，走不了
```

**该建**：WHERE 高频列、JOIN 连接列、ORDER BY/GROUP BY 组合、唯一业务键。
**不该建**：写多读少的表滥建、低区分度列（如 role 只有两种值）单建、重复冗余索引（有 `(a)` 就别再建 `(a,b)` 的 `(a)` 前缀）。

### 7.2 查看 / 清理

```sql
SHOW INDEX FROM trajectories;                                -- 看已有索引
SELECT * FROM sys.schema_unused_indexes
WHERE object_schema = 'microtrip';                           -- 8.0 sys 库：长期没用的索引
DROP INDEX idx_traj_city ON trajectories;                    -- 删除（在线 DDL，不锁表）
```

---

## 8. EXPLAIN 执行计划（调优第一步）

任何慢 SQL，先 `EXPLAIN` 再谈优化：

```sql
EXPLAIN SELECT t.* FROM trajectories t
JOIN users u ON t.user_id = u.id
WHERE u.phone = '13900000001'
ORDER BY t.start_time DESC;
```

重点看这几列：

| 列 | 好的样子 | 危险信号 |
|---|---|---|
| `type` | `const` > `eq_ref` > `ref` > `range` | `index`（扫全索引）、`ALL`（全表扫描） |
| `key` | 命中预期的索引名 | `NULL`（没用上索引） |
| `rows` | 越小越好（预估扫描行数） | 与数据量同量级 |
| `Extra` | `Using index`（覆盖索引，最佳） | `Using filesort` / `Using temporary`（额外排序/临时表，优先优化对象） |

**常见"索引失效"五连**（EXPLAIN 显示 key=NULL 时逐一对照）：

1. 对列做运算/函数：`WHERE FROM_UNIXTIME(start_time/1000) > '2026-01-01'` ✗
   → 改成对常量运算：`WHERE start_time > UNIX_TIMESTAMP('2026-01-01') * 1000` ✓
2. 隐式类型转换：varchar 列用数字比 `WHERE phone = 13900000001` ✗ → `WHERE phone = '13900000001'` ✓
3. 前导 `%` 的 LIKE（见 §1.1④）
4. `OR` 两侧有列没索引 → 拆成 UNION
5. 联合索引不满足最左前缀

**慢查询定位**：

```sql
SHOW VARIABLES LIKE 'slow_query%';    -- 慢日志开关与文件位置
SET GLOBAL slow_query_log = ON;       -- 临时打开（本地排查用）
SET GLOBAL long_query_time = 0.5;     -- 超过 0.5s 记录
```

---

## 9. 常用管理语句速查

```sql
-- 库/表体检
SHOW TABLES;                                             -- 列出表
SHOW CREATE TABLE trajectories\G                         -- 看建表 DDL（含索引）
SELECT table_name, ROUND(data_length/1024/1024, 1) AS 数据MB,
       ROUND(index_length/1024/1024, 1) AS 索引MB, table_rows
FROM information_schema.tables WHERE table_schema = 'microtrip';

-- 行数精确统计（information_schema 的 table_rows 是估算值，只可参考）
SELECT 'users' t, COUNT(*) FROM users
UNION ALL SELECT 'trajectories', COUNT(*) FROM trajectories
UNION ALL SELECT 'trajectory_points', COUNT(*) FROM trajectory_points;

-- 当前连接与锁等待
SHOW PROCESSLIST;                                        -- 谁连着、在跑什么
SELECT * FROM sys.innodb_lock_waits;                     -- 8.0 锁等待现场
KILL <Id>;                                               -- 掐掉失控查询（PROCESSLIST 的 Id）
```

---

## 10. MicroTrip 业务查询实战模板

```sql
-- ① 用户总里程/总时长（「我的」页 stats 卡片同款口径）
SELECT COUNT(*) AS 轨迹数,
       ROUND(SUM(distance)/1000, 1) AS 总里程km,
       ROUND(SUM(duration)/3600, 1) AS 总时长h
FROM trajectories WHERE user_id = 'u_000001';

-- ② 最近 7 天每日里程（柱状图数据；无记录的日子应用层补 0）
SELECT FROM_UNIXTIME(start_time/1000, '%m-%d') AS 日,
       ROUND(SUM(distance)/1000, 1) AS km
FROM trajectories
WHERE user_id = 'u_000001' AND start_time >= (UNIX_TIMESTAMP() - 7*86400) * 1000
GROUP BY 日 ORDER BY MIN(start_time);

-- ③ 单条轨迹的 GPS 点按序还原（点表为准确来源，points_json 是冗余快照）
SELECT lat, lng, spd, alt, ts
FROM trajectory_points
WHERE trajectory_id = 't_xxx' ORDER BY seq;

-- ④ JSON 字段查询（points_json 里的点数，不用拆行）
SELECT id, JSON_LENGTH(points_json) AS 点数 FROM trajectories LIMIT 10;

-- ⑤ 大轨迹体检：json 点数与行表点数不一致 = 双写不同步（巡检口径）
SELECT t.id, JSON_LENGTH(t.points_json) AS json点,
       (SELECT COUNT(*) FROM trajectory_points p WHERE p.trajectory_id = t.id) AS 行表点
FROM trajectories t
HAVING json点 <> 行表点;

-- ⑥ 找最近一次同步的轨迹（游标分页的起点）
SELECT id, start_time FROM trajectories
WHERE user_id = 'u_000001' ORDER BY synced_at DESC LIMIT 1;
```

---

## 11. 一页速记（贴工位版）

| 场景 | 第一反应 |
|---|---|
| 慢查询 | `EXPLAIN` → 看 type/key/rows/Extra |
| 深分页 | 游标分页（WHERE 排序列 < 上一页末值） |
| 存在/不存在 | `EXISTS / NOT EXISTS` |
| 每组 TopN / 去重 | `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` |
| 幂等写入 | `ON DUPLICATE KEY UPDATE` |
| 批量删 | `LIMIT 10000` 分批 + 循环 |
| 改数据 | 先 SELECT 看影响面 → 事务 → 核对 ROW_COUNT → COMMIT |
| 建索引 | 等值列在前、范围列在后；最左前缀 |
| 大字段 | 查询别 `SELECT *`（points_json 是 text） |
| 动手前 | `./scripts/backup.sh --local` 先备份 |
