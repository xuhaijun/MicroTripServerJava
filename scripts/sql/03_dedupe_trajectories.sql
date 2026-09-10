-- ============================================================
-- 03_dedupe_trajectories.sql — 重复轨迹去重（保留每组最早入库的一条）
-- ------------------------------------------------------------
-- ⚠️ 高危操作：先备份再执行（见 02 脚本头部说明）。
-- 判重口径：同 user_id + 同 start_time 视为重复（可按需改成 id/城市等）。
-- 保留策略：ROW_NUMBER 按 synced_at 升序，第 1 条保留，其余删除。
--
-- 用法：交互式连接后逐段执行。C1 删除前必须先看 D1 的摸底结果。
-- ============================================================

-- ---------- D1. 摸底：哪些要删（只读，先看清楚） ----------
SELECT user_id, start_time, COUNT(*) AS 重复数,
       GROUP_CONCAT(id ORDER BY synced_at) AS 各版本_按入库先后
FROM trajectories
GROUP BY user_id, start_time
HAVING 重复数 > 1;

-- ---------- D2. 预演：列出将被删除的 id（只读！与 D3 的 DELETE 严格同条件） ----------
SELECT x.id AS 将删除, x.重复组
FROM (
  SELECT id, CONCAT(user_id, '@', start_time) AS 重复组,
         ROW_NUMBER() OVER (PARTITION BY user_id, start_time
                            ORDER BY synced_at, id) AS rn
  FROM trajectories
) x
WHERE x.rn > 1;

-- ---------- D3. 执行（先删点表防孤儿，再删主表，同一事务） ----------
START TRANSACTION;
DELETE p FROM trajectory_points p
  JOIN trajectories t ON p.trajectory_id = t.id
  WHERE t.id IN (
    SELECT id FROM (
      SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id, start_time
                                    ORDER BY synced_at, id) AS rn
      FROM trajectories
    ) w WHERE w.rn > 1
  );
DELETE t FROM trajectories t
  JOIN (
    SELECT id FROM (
      SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id, start_time
                                    ORDER BY synced_at, id) AS rn
      FROM trajectories
    ) w WHERE w.rn > 1
  ) dup ON t.id = dup.id;
SELECT ROW_COUNT() AS 已删主表行;
COMMIT;   -- 数量与 D2 预演不一致就 ROLLBACK

-- ---------- D4. 收尾 ----------
-- 重跑 01_health_check.sql 的 B6（应 0 行）与 B1（确认无孤儿）。
