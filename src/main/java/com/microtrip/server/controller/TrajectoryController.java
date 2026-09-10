package com.microtrip.server.controller;

import com.microtrip.server.dto.TrajectoryDetailDto;
import com.microtrip.server.dto.TrajectoryListResponse;
import com.microtrip.server.dto.TrajectoryStatsDto;
import com.microtrip.server.dto.TrajectorySyncBatchRequest;
import com.microtrip.server.dto.TrajectorySyncBatchResponse;
import com.microtrip.server.dto.TrajectorySyncRequest;
import com.microtrip.server.dto.TrajectorySyncResult;
import com.microtrip.server.security.JwtPrincipal;
import com.microtrip.server.service.TrajectoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 轨迹接口（均需 Bearer）。与 Node 版路由、响应结构对齐。
 *
 * <p><b>路由顺序注意</b>：{@code /list}、{@code /stats} 都是字面量路径，
 * Spring MVC 的路径比较器会让它们优先于 {@code /{id}} 通配，
 * 因此 GET {@code /trajectory/stats} 不会被当成「id = stats」的详情请求。</p>
 */
@RestController
@RequestMapping({"/trajectory", "/api/v1/trajectory"})
public class TrajectoryController {

    private final TrajectoryService service;

    public TrajectoryController(TrajectoryService service) {
        this.service = service;
    }

    /**
     * POST /trajectory/sync → { ok: true, id, points, stops }
     *
     * <p>{@code points} / {@code stops} 为本轮新增的<b>附加字段</b>（原有 {@code ok} / {@code id}
     * 不变，客户端可忽略），用于让客户端确认实际落库条数 —— 超过服务端上限（20000 点 / 500 停留点）
     * 会被截断，此时 {@code points} &lt; 请求条数。</p>
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(@Valid @RequestBody TrajectorySyncRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        TrajectorySyncResult result = service.sync(req.trajectory(), principal.sub());
        return Map.of("ok", true,
                "id", result.id(),
                "points", result.points(),
                "stops", result.stops());
    }

    /**
     * POST /trajectory/sync-batch → 批量同步（App 离线补传）。
     *
     * <p>逐条独立事务：部分失败不影响已成功的记录，响应里的 {@code results}
     * 给出每条成败与原因，客户端只需重试失败项。</p>
     */
    @PostMapping("/sync-batch")
    public TrajectorySyncBatchResponse syncBatch(@Valid @RequestBody TrajectorySyncBatchRequest req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return service.syncBatch(req.trajectories(), principal.sub());
    }

    /**
     * GET /trajectory/list?page&pageSize&from&to → 摘要列表。
     *
     * @param from 开始时间下界（毫秒时间戳，含），可选
     * @param to   开始时间上界（毫秒时间戳，含），可选
     */
    @GetMapping("/list")
    public TrajectoryListResponse list(@AuthenticationPrincipal JwtPrincipal principal,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize,
                                       @RequestParam(required = false) Long from,
                                       @RequestParam(required = false) Long to) {
        return service.list(principal.sub(), page, pageSize, from, to);
    }

    /** GET /trajectory/stats → 汇总统计（轨迹数 / 总里程 / 总时长 / 累计爬升下降 / 采样点数） */
    @GetMapping("/stats")
    public TrajectoryStatsDto stats(@AuthenticationPrincipal JwtPrincipal principal) {
        return service.stats(principal.sub());
    }

    /** GET /trajectory/:id → { trajectory: 完整详情 } */
    @GetMapping("/{id}")
    public Map<String, TrajectoryDetailDto> detail(@AuthenticationPrincipal JwtPrincipal principal,
                                                   @PathVariable String id) {
        return Map.of("trajectory", service.detail(principal.sub(), id));
    }

    /** DELETE /trajectory/:id → { ok: true } */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(@AuthenticationPrincipal JwtPrincipal principal,
                                                      @PathVariable String id) {
        service.remove(principal.sub(), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
