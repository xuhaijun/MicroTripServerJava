package com.microtrip.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.common.BizException;
import com.microtrip.server.domain.Trajectory;
import com.microtrip.server.domain.TrajectoryPoint;
import com.microtrip.server.dto.*;
import com.microtrip.server.repository.TrajectoryPointRepository;
import com.microtrip.server.repository.TrajectoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轨迹业务：同步（幂等 upsert）、批量同步、列表（分页摘要 + 时间范围）、详情、统计、删除。
 * 与 App 端 TrajectoryRecord.toMap() 字段严格对齐。
 *
 * <p><b>落库结构</b>：同步时除写入 {@code trajectories.points_json}（对外契约规范存储）外，
 * 还将每个 GPS 点写入独立表 {@code trajectory_points}，供 SQL 侧范围检索 / 密度统计；
 * 删除轨迹时按 {@code trajectory_id} 级联清理该表。</p>
 *
 * <p><b>本轮性能治理（2026-09-10）</b></p>
 * <ol>
 *   <li>列表 / 统计改走构造器投影：{@code points_json} 大字段不再参与列表查询；</li>
 *   <li>批量删除改 JPQL 直发 SQL，不再「先 SELECT 全量实体再逐条 DELETE」；</li>
 *   <li>账号注销补删 GPS 点，消除孤儿行（原先只删轨迹，points 表会残留）；</li>
 *   <li>JDBC 批量写入参数见 {@code application.yml}（batch_size / order_inserts），
 *       万级点的 INSERT 由「两万次单条」合并为「数十次批次」；</li>
 *   <li>列表 / 详情标注 {@code readOnly}，Hibernate 跳过脏检查快照，减少内存与 CPU。</li>
 * </ol>
 */
@Service
public class TrajectoryService {

    /** 单条轨迹 GPS 点上限（超出部分截断，回执里的 points 会小于请求条数） */
    private static final int MAX_POINTS = 20000;
    /** 单条轨迹停留点上限 */
    private static final int MAX_STOPS = 500;
    /** 批量同步一次最多条数：防止单次请求长时间占用连接与事务 */
    private static final int MAX_BATCH = 20;

    private final TrajectoryRepository repository;
    private final TrajectoryPointRepository pointRepository;
    private final ObjectMapper objectMapper;

    /**
     * 批量同步的逐条事务模板。
     *
     * <p>为什么用 {@code TransactionTemplate} 而不是 {@code @Transactional(REQUIRES_NEW)} 注解：
     * 注解走 AOP 代理，类内自调用不会生效（典型陷阱）；编程式事务把「一条轨迹一个事务」
     * 写得明明白白，一条失败不影响其他条已提交的结果。</p>
     */
    private final TransactionTemplate txTemplate;

    public TrajectoryService(TrajectoryRepository repository,
                             TrajectoryPointRepository pointRepository,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager txManager) {
        this.repository = repository;
        this.pointRepository = pointRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================== 同步 ====================

    /**
     * 单条同步（按 id 幂等 upsert）。
     *
     * @return 回执（轨迹 id + 实际落库的点数 / 停留点数）
     */
    @Transactional
    public TrajectorySyncResult sync(TrajectorySyncDto raw, String userId) {
        return doSync(raw, userId);
    }

    /**
     * 批量同步（App 离线补传）：逐条独立事务，部分失败不回滚已成功的。
     *
     * @param list   待同步轨迹（最多 {@value #MAX_BATCH} 条）
     * @param userId 归属用户（来自 JWT）
     */
    public TrajectorySyncBatchResponse syncBatch(List<TrajectorySyncDto> list, String userId) {
        if (list == null || list.isEmpty()) {
            throw BizException.badRequest("trajectories 不能为空");
        }
        if (list.size() > MAX_BATCH) {
            throw BizException.badRequest("单次最多同步 " + MAX_BATCH + " 条轨迹，请分批提交");
        }

        List<TrajectorySyncBatchResponse.Item> results = new ArrayList<>(list.size());
        // 同一批内 id 去重：重复 id 会互相覆盖，提前拦下比事后排查省事
        Set<String> seen = new HashSet<>();
        int succeeded = 0;

        for (TrajectorySyncDto dto : list) {
            String id = dto == null ? null : dto.id();
            if (id == null || id.isBlank()) {
                results.add(TrajectorySyncBatchResponse.Item.failure(null, "缺少轨迹 id"));
                continue;
            }
            if (!seen.add(id)) {
                results.add(TrajectorySyncBatchResponse.Item.failure(id, "同一批次内 id 重复"));
                continue;
            }
            try {
                Integer points = txTemplate.execute(status -> doSync(dto, userId).points());
                results.add(TrajectorySyncBatchResponse.Item.success(id, points == null ? 0 : points));
                succeeded++;
            } catch (Exception e) {
                // 单条失败：记录原因继续处理后续，客户端只需重试失败项
                results.add(TrajectorySyncBatchResponse.Item.failure(id, rootMessage(e)));
            }
        }

        return new TrajectorySyncBatchResponse(
                true, list.size(), succeeded, list.size() - succeeded, results);
    }

    /**
     * 同步的实际工作（不含事务边界，由调用方提供事务）。
     * 幂等：同 id 覆盖（先删 points 子表再批量插入），天然支持断点重试。
     */
    private TrajectorySyncResult doSync(TrajectorySyncDto raw, String userId) {
        if (raw == null || raw.id() == null || raw.id().isBlank()) {
            throw BizException.badRequest("缺少轨迹 id");
        }

        // 归一化 GPS 点（上限 20000，缺省值兜底）
        List<TrajectoryPointDto> pts = new ArrayList<>();
        if (raw.pts() != null) {
            for (TrajectoryPointDto p : raw.pts()) {
                if (pts.size() >= MAX_POINTS) break;
                pts.add(new TrajectoryPointDto(
                        orZero(p.lat()), orZero(p.lng()), orZero(p.ts()),
                        p.alt(), p.spd(), p.acc(), p.hdg()));
            }
        }

        // 归一化停留点（上限 500，rad 默认 100）
        List<TrajectoryStopDto> stops = new ArrayList<>();
        if (raw.stops() != null) {
            for (TrajectoryStopDto s : raw.stops()) {
                if (stops.size() >= MAX_STOPS) break;
                stops.add(new TrajectoryStopDto(
                        orZero(s.lat()), orZero(s.lng()),
                        orZero(s.arr()), orZero(s.dep()), orZero(s.dur()),
                        s.rad() == null ? 100.0 : s.rad(),
                        s.label(), s.addr()));
            }
        }

        String pointsJson = writeJson(pts);
        String stopsJson = writeJson(stops);

        Trajectory entity = Trajectory.builder()
                .id(raw.id())
                .userId(userId)
                .startTime(orZero(raw.start()))
                .endTime(orZero(raw.end()))
                .distance(orZero(raw.distance()))
                .duration(orZero(raw.duration()))
                .maxAlt(raw.maxAlt())
                .minAlt(raw.minAlt())
                .ascent(raw.ascent())
                .descent(raw.descent())
                .avgSpeed(raw.avgSpeed())
                .title(raw.title())
                .note(raw.note())
                .city(raw.city())
                .pointsJson(pointsJson)
                .stopsJson(stopsJson)
                .syncedAt(System.currentTimeMillis())
                .build();

        // JPA save：同 id 自动覆盖（幂等），天然支持断点重试
        repository.save(entity);

        // 幂等写入独立 points 表：一条 SQL 清旧 → 批量插入新点
        // （批量大小由 hibernate.jdbc.batch_size 控制，见 application.yml）
        pointRepository.deleteAllByTrajectoryId(raw.id());
        List<TrajectoryPoint> batch = new ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            TrajectoryPointDto p = pts.get(i);
            batch.add(TrajectoryPoint.builder()
                    .trajectoryId(raw.id())
                    .seq(i)
                    .lat(orZero(p.lat()))
                    .lng(orZero(p.lng()))
                    .ts(orZero(p.ts()))
                    .alt(p.alt())
                    .spd(p.spd())
                    .acc(p.acc())
                    .hdg(p.hdg())
                    .build());
        }
        pointRepository.saveAll(batch);

        return new TrajectorySyncResult(raw.id(), pts.size(), stops.size());
    }

    // ==================== 查询 ====================

    /** 某轨迹落库的 GPS 点数（拆表验证 / 统计） */
    @Transactional(readOnly = true)
    public long pointCount(String trajectoryId) {
        return pointRepository.countByTrajectoryId(trajectoryId);
    }

    /** 区域内 GPS 点检索（Geo 围栏 / 热力密度等超长场景，替代解析 points_json） */
    @Transactional(readOnly = true)
    public List<TrajectoryPoint> pointsInRegion(String trajectoryId,
                                                double minLat, double maxLat,
                                                double minLng, double maxLng) {
        return pointRepository.findByTrajectoryIdAndLatBetweenAndLngBetweenOrderBySeqAsc(
                trajectoryId, minLat, maxLat, minLng, maxLng);
    }

    /**
     * 列表（摘要，不含 pts，分页，开始时间倒序）。
     *
     * @param from 开始时间下界（毫秒），null 表示不限
     * @param to   开始时间上界（毫秒），null 表示不限
     */
    @Transactional(readOnly = true)
    public TrajectoryListResponse list(String userId, int page, int pageSize,
                                       Long from, Long to) {
        int p = Math.max(1, page);
        int size = Math.min(50, Math.max(1, pageSize));
        Pageable pageable = PageRequest.of(p - 1, size);

        Page<TrajectorySummaryDto> pageData;
        if (from != null || to != null) {
            // 单边限定时用 Long.MIN/MAX 补另一边，保持 SQL 形态固定（走复合索引）
            long lo = from == null ? Long.MIN_VALUE : from;
            long hi = to == null ? Long.MAX_VALUE : to;
            if (lo > hi) {
                throw BizException.badRequest("from 不能大于 to");
            }
            pageData = repository.findSummariesBetween(userId, lo, hi, pageable);
        } else {
            pageData = repository.findSummaries(userId, pageable);
        }

        return new TrajectoryListResponse(
                pageData.getContent(), p, size,
                pageData.getTotalElements(), pageData.hasNext());
    }

    /** 汇总统计（我的 / 出行足迹页） */
    @Transactional(readOnly = true)
    public TrajectoryStatsDto stats(String userId) {
        TrajectoryStatsDto stats = repository.findStats(userId);
        if (stats != null) return stats;
        // 理论上聚合查询必回一行；H2 / MySQL 极端情况下仍兜底，避免 500
        return new TrajectoryStatsDto(0L, 0.0, 0L, 0.0, 0.0,
                null, null, null, null, null, 0L);
    }

    /** 详情（含 GPS 点与停留点） */
    @Transactional(readOnly = true)
    public TrajectoryDetailDto detail(String userId, String id) {
        Trajectory t = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BizException.notFound("轨迹不存在"));
        return new TrajectoryDetailDto(
                t.getId(), t.getStartTime(), t.getEndTime(),
                readPoints(t.getPointsJson()),
                t.getDistance(), t.getDuration(),
                readStops(t.getStopsJson()),
                t.getMaxAlt(), t.getMinAlt(), t.getAscent(), t.getDescent(),
                t.getAvgSpeed(), t.getTitle(), t.getNote(), t.getCity());
    }

    // ==================== 删除 ====================

    /** 删除（归属校验）；级联清理独立 points 表 */
    @Transactional
    public void remove(String userId, String id) {
        Trajectory t = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BizException.notFound("轨迹不存在"));
        pointRepository.deleteAllByTrajectoryId(id);
        repository.delete(t);
    }

    // ---------------- 工具方法 ----------------

    /** 取异常链最内层的可读消息（批量同步逐条回执用） */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    private Double orZero(Double v) {
        return (v == null || !Double.isFinite(v)) ? 0.0 : v;
    }

    private Long orZero(Long v) {
        return (v == null || v == 0) ? 0L : v; // 非有限判断对 Long 不需要，仅做 null 兜底
    }

    private List<TrajectoryPointDto> readPoints(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            TrajectoryPointDto[] arr = objectMapper.readValue(json, TrajectoryPointDto[].class);
            return List.of(arr);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<TrajectoryStopDto> readStops(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            TrajectoryStopDto[] arr = objectMapper.readValue(json, TrajectoryStopDto[].class);
            return List.of(arr);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
