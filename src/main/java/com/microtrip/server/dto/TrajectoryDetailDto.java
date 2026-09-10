package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 轨迹完整详情（含 GPS 点与停留点）。用于 GET /trajectory/:id。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectoryDetailDto(
        String id,
        Long start,
        Long end,
        List<TrajectoryPointDto> pts,
        Double distance,
        Long duration,
        List<TrajectoryStopDto> stops,
        Double maxAlt,
        Double minAlt,
        Double ascent,
        Double descent,
        Double avgSpeed,
        String title,
        String note,
        String city) {
}
