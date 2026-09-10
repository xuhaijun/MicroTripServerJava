package com.microtrip.server.dto;

import java.util.List;

/**
 * 轨迹列表响应：{ list, page, pageSize, total, hasMore }
 */
public record TrajectoryListResponse(
        List<TrajectorySummaryDto> list,
        int page,
        int pageSize,
        long total,
        boolean hasMore) {
}
