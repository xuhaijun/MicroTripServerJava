-- ============================================================
-- 01_health_check.sql — 数据健康巡检 + 脏数据摸底（全部只读，随便跑）
-- ------------------------------------------------------------
-- 用法：
--   ./.../mysql.exe -umicrotrip -pmicrotrip123 --default-character-set=utf8mb4 \
--     microtrip < scripts/sql/01_health_check.sql
-- 或交互式连接后：SOURCE scripts/sql/01_health_check.sql;
-- 建议每周跑一遍；发现脏数据后按 02/03 脚本处理（先备份！见《数据库操作指南》§7）。
-- ============================================================

-- ---------- A. 总量 ----------
SELECT (SELECT COUNT(*) FROM users)                AS 用户数,
       (SELECT COUNT(*) FROM trajectories)         AS 轨迹数,
       (SELECT COUNT(*) FROM trajectory_points)    AS GPS点数;

-- ---------- B. 脏数据摸底（六连，全 0 才算干净） ----------
-- B1. 孤儿 GPS 点：主表已无对应轨迹（多由跳序删除造成）
SELECT COUNT(*) AS B1_孤儿点
FROM trajectory_points p
LEFT JOIN trajectories t ON p.trajectory_id = t.id
WHERE t.id IS NULL;

-- B2. 零点轨迹：points_json 为空数组（历史 pts 键名坑的遗留）
SELECT COUNT(*) AS B2_零点轨迹 FROM trajectories WHERE JSON_LENGTH(points_json) = 0;

-- B3. 单位异常：步行轨迹 > 100km 视为可疑（distance 单位是米）
SELECT id, ROUND(distance/1000, 1) AS km FROM trajectories
WHERE distance > 100000 OR distance < 0;

-- B4. 时间倒挂：结束早于开始
SELECT id, start_time, end_time FROM trajectories WHERE end_time < start_time;

-- B5. 脏手机号
SELECT id, phone FROM users WHERE phone NOT REGEXP '^1[0-9]{10}$';

-- B6. 重复轨迹：同用户同开始时间多条
SELECT user_id, start_time, COUNT(*) AS c, GROUP_CONCAT(id) AS ids
FROM trajectories GROUP BY user_id, start_time HAVING c > 1;

-- ---------- C. 体积巡检 ----------
SELECT id, JSON_LENGTH(points_json) AS json点数,
       (SELECT COUNT(*) FROM trajectory_points p WHERE p.trajectory_id = t.id) AS 行表点数
FROM trajectories t ORDER BY json点数 DESC LIMIT 5;

-- MySQL 专属（H2 跳过）：
-- SELECT table_name, ROUND(data_length/1024/1024,1) AS 数据MB, table_rows
-- FROM information_schema.tables WHERE table_schema='microtrip';
