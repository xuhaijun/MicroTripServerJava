-- ============================================================
-- 02_cleanup_orphans.sql — 脏数据清理（会改数据！先看使用说明）
-- ------------------------------------------------------------
-- ⚠️ 使用前必做（顺序不能省）：
--   1. 先跑 01_health_check.sql 确认脏数据类型与数量
--   2. 备份：mysqldump ... microtrip trajectories trajectory_points > bak.sql
--      或建备份表：CREATE TABLE bak_points_20260910 AS SELECT * FROM trajectory_points;
--   3. 逐段执行（本文件按段分隔），每段观察输出再跑下一段
--   4. 事务包裹：删错了 ROLLBACK 还能救
--
-- 用法：交互式连接后逐段 SOURCE/粘贴执行；不要整文件一把梭。
-- ============================================================

-- ---------- C1. 清孤儿 GPS 点（安全：主表都没有了，点必是垃圾） ----------
START TRANSACTION;
DELETE p FROM trajectory_points p
LEFT JOIN trajectories t ON p.trajectory_id = t.id
WHERE t.id IS NULL;
SELECT ROW_COUNT() AS 已删孤儿点;   -- 核对数量是否与巡检 B1 一致
COMMIT;   -- 数字不对就改 ROLLBACK

-- ---------- C2. 清零点轨迹（连同其点表，防造出新孤儿） ----------
START TRANSACTION;
-- 先删这些轨迹的点表行
DELETE p FROM trajectory_points p
  JOIN trajectories t ON p.trajectory_id = t.id
  WHERE JSON_LENGTH(t.points_json) = 0;
-- 再删主表
DELETE FROM trajectories WHERE JSON_LENGTH(points_json) = 0;
COMMIT;

-- ---------- C3. 修时间倒挂（end_time 用 start + duration 兜底） ----------
START TRANSACTION;
UPDATE trajectories SET end_time = start_time + duration * 1000
WHERE end_time < start_time;
SELECT ROW_COUNT() AS 已修正; 
COMMIT;

-- ---------- C4. 回填缺失 avg_speed（distance米/duration秒*3.6 = km/h） ----------
START TRANSACTION;
UPDATE trajectories
SET avg_speed = ROUND(distance / duration * 3.6, 2)
WHERE (avg_speed IS NULL OR avg_speed = 0) AND duration > 0;
COMMIT;

-- ---------- 收尾：重跑 01_health_check.sql，六连应全部归零 ----------
