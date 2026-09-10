package com.microtrip.server.repository;

import com.microtrip.server.domain.TrajectoryPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 轨迹 GPS 点仓储（{@code trajectory_points} 拆表读侧）。
 *
 * <p><b>为什么删除不用派生方法</b>：{@code void deleteByTrajectoryId(String)} 这类派生删除，
 * Spring Data 会先 {@code SELECT} 出全部匹配实体、逐个 {@code EntityManager.remove()}，
 * 最后才逐条发 DELETE —— 一条 20000 点的轨迹会先在内存里建 20000 个实体
 * （脏检查 + 刷写队列），删除耗时与内存占用都是灾难级的。
 * 这里统一改为 JPQL {@code delete}，直接下发 <b>一条</b> SQL。</p>
 */
@Repository
public interface TrajectoryPointRepository extends JpaRepository<TrajectoryPoint, Long> {

    /**
     * 级联清理：删除轨迹时按其 trajectory_id 一次性删除全部点（单条 SQL）。
     *
     * @return 受影响行数
     */
    @Modifying
    @Query("delete from TrajectoryPoint p where p.trajectoryId = :trajectoryId")
    int deleteAllByTrajectoryId(@Param("trajectoryId") String trajectoryId);

    /**
     * 账号注销：删除该用户全部轨迹下的 GPS 点。
     *
     * <p>必须<b>先于</b>删除轨迹调用：子查询依赖 {@code trajectories} 中的归属关系，
     * 顺序颠倒会删不掉而留下孤儿点。单条 SQL，不加载任何实体。</p>
     *
     * @return 受影响行数
     */
    @Modifying
    @Query("""
            delete from TrajectoryPoint p
            where p.trajectoryId in (select t.id from Trajectory t where t.userId = :userId)
            """)
    int deleteAllByUserId(@Param("userId") String userId);

    /** 统计某轨迹落库的点数（验证拆表写入） */
    long countByTrajectoryId(String trajectoryId);

    /** 按序号升序取某轨迹全部点（读侧优化，替代解析 points_json） */
    List<TrajectoryPoint> findByTrajectoryIdOrderBySeqAsc(String trajectoryId);

    /** 区域内点检索（Geo 围栏 / 热力密度等超长场景）：在轨迹内按经纬度矩形过滤 */
    List<TrajectoryPoint> findByTrajectoryIdAndLatBetweenAndLngBetweenOrderBySeqAsc(
            String trajectoryId, double minLat, double maxLat, double minLng, double maxLng);
}
