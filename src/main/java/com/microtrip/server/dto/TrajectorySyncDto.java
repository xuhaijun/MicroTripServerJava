package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 轨迹同步入参（请求体 {@code { trajectory: {...} }} 中的 trajectory 对象）。
 *
 * <p>字段命名已统一为驼峰全称，并与 App 端 {@code TrajectoryRecord.toMap()}
 * 对齐；同时兼容旧别名（dist/dur/asc/desc/avgSpd）以向前兼容。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectorySyncDto(
        String id,
        Long start,
        Long end,
        List<TrajectoryPointDto> pts,
        @JsonAlias("dist") Double distance,
        @JsonAlias("dur") Long duration,
        List<TrajectoryStopDto> stops,
        Double maxAlt,
        Double minAlt,
        @JsonAlias("asc") Double ascent,
        @JsonAlias("desc") Double descent,
        @JsonAlias("avgSpd") Double avgSpeed,
        String title,
        String note,
        String city) {
}
