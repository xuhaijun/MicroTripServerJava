package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 轨迹列表摘要（不含 GPS 点）。用于 GET /trajectory/list。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectorySummaryDto(
        String id,
        Long start,
        Long end,
        Double distance,
        Long duration,
        Double avgSpeed,
        String title,
        String note,
        String city,
        Long syncedAt) {
}
