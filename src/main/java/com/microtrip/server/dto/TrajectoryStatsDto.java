package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 轨迹汇总统计（GET /trajectory/stats）。
 *
 * <p>为 App「我的 / 出行足迹」页提供一次性聚合结果，避免客户端把列表拉全再在本地求和：
 * 聚合在 SQL 侧完成（COUNT / SUM / MAX），只回传一行。</p>
 *
 * <p><b>字段单位</b>：与单条轨迹字段<b>原样一致、不做换算</b>（{@code totalDistance}
 * 与 {@code TrajectorySummaryDto.distance} 同单位，{@code totalDuration} 与
 * {@code duration} 同单位）。刻意不做 km / 小时换算，避免与服务端已有契约产生歧义。</p>
 *
 * <p>字段可空：无任何轨迹时 {@code count = 0}，其余聚合值为 {@code 0} 或 {@code null}
 * （{@code null} 表示「没有数据可聚合」，如 {@code maxDistance}）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectoryStatsDto(
        /** 轨迹总数 */
        Long count,
        /** 累计里程（与 distance 同单位原样累加） */
        Double totalDistance,
        /** 累计时长（与 duration 同单位原样累加） */
        Long totalDuration,
        /** 累计爬升 */
        Double totalAscent,
        /** 累计下降 */
        Double totalDescent,
        /** 单条最长里程 */
        Double maxDistance,
        /** 单条最高平均速度 */
        Double maxAvgSpeed,
        /** 最早一条的开始时间（毫秒时间戳） */
        Long firstStart,
        /** 最近一条的开始时间（毫秒时间戳） */
        Long lastStart,
        /** 最近一次同步时间（毫秒时间戳） */
        Long lastSyncedAt,
        /** 该用户全部轨迹的 GPS 采样点总数（trajectory_points 表） */
        Long totalPoints) {
}
