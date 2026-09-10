package com.microtrip.server.repository;

import com.microtrip.server.domain.Trajectory;
import com.microtrip.server.dto.TrajectoryStatsDto;
import com.microtrip.server.dto.TrajectorySummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 轨迹仓储。
 *
 * <p><b>性能约定（重要）</b>：{@code trajectories} 表上的 {@code points_json} /
 * {@code stops_json} 是 TEXT 大字段（单条最长 20000 个 GPS 点，JSON 可达数 MB）。
 * 因此：</p>
 * <ul>
 *   <li>列表 / 统计类查询<b>一律走构造器投影</b>，只 SELECT 需要的列，
 *       不返回 {@code Trajectory} 实体 —— 实体查询会把大字段整列读进内存，
 *       一页 20 条就是几十 MB 的无用 IO 与 GC 压力；</li>
 *   <li>只有「详情」按 id 取单条时才查实体；</li>
 *   <li>批量删除一律用 JPQL {@code delete} 直发 SQL，不用派生删除
 *       （派生删除会先把实体全部 SELECT 出来再逐条 DELETE）。</li>
 * </ul>
 */
@Repository
public interface TrajectoryRepository extends JpaRepository<Trajectory, String> {

    /**
     * 列表摘要（按用户，开始时间倒序，分页）。
     * 构造器投影 = 只读取 10 个摘要列，跳过 points_json / stops_json。
     */
    @Query("""
            select new com.microtrip.server.dto.TrajectorySummaryDto(
                t.id, t.startTime, t.endTime, t.distance, t.duration,
                t.avgSpeed, t.title, t.note, t.city, t.syncedAt)
            from Trajectory t
            where t.userId = :userId
            order by t.startTime desc
            """)
    Page<TrajectorySummaryDto> findSummaries(@Param("userId") String userId, Pageable pageable);

    /**
     * 列表摘要 + 开始时间范围过滤（「近 7 天」/「本月」等场景）。
     *
     * <p>刻意写成独立方法而不是「可选参数拼接」：SQL 形态固定，
     * {@code (user_id, start_time)} 复合索引才能稳定命中范围扫描。</p>
     */
    @Query("""
            select new com.microtrip.server.dto.TrajectorySummaryDto(
                t.id, t.startTime, t.endTime, t.distance, t.duration,
                t.avgSpeed, t.title, t.note, t.city, t.syncedAt)
            from Trajectory t
            where t.userId = :userId
              and t.startTime >= :from and t.startTime <= :to
            order by t.startTime desc
            """)
    Page<TrajectorySummaryDto> findSummariesBetween(@Param("userId") String userId,
                                                    @Param("from") long from,
                                                    @Param("to") long to,
                                                    Pageable pageable);

    /**
     * 一次性聚合统计（COUNT / SUM / MAX），只回一行，避免把全部轨迹拉到应用层求和。
     *
     * <p>采样点总数用相关子查询在同一 SQL 内取出，省掉一次往返；
     * 无轨迹时聚合仍返回一行（count = 0，其余聚合值为 0 或 null）。</p>
     */
    @Query("""
            select new com.microtrip.server.dto.TrajectoryStatsDto(
                count(t),
                coalesce(sum(t.distance), 0.0),
                coalesce(sum(t.duration), 0L),
                coalesce(sum(t.ascent), 0.0),
                coalesce(sum(t.descent), 0.0),
                max(t.distance),
                max(t.avgSpeed),
                min(t.startTime),
                max(t.startTime),
                max(t.syncedAt),
                (select count(p) from TrajectoryPoint p
                  where p.trajectoryId in (select t2.id from Trajectory t2 where t2.userId = :userId)))
            from Trajectory t
            where t.userId = :userId
            """)
    TrajectoryStatsDto findStats(@Param("userId") String userId);

    /** 归属校验：必须同用户才能读取/删除 */
    Optional<Trajectory> findByIdAndUserId(String id, String userId);

    /**
     * 账号注销：批量删除该用户全部轨迹（直发一条 DELETE）。
     * 由调用方在事务内执行，保证与账号删除同成败。
     *
     * @return 受影响行数
     */
    @Modifying
    @Query("delete from Trajectory t where t.userId = :userId")
    int deleteAllByUserId(@Param("userId") String userId);
}
