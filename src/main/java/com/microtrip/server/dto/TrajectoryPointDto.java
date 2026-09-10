package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GPS 点（轨迹 pts 元素）。
 * { lat, lng, ts, alt?, spd?, acc?, hdg? }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectoryPointDto(
        Double lat,
        Double lng,
        Long ts,
        Double alt,
        Double spd,
        Double acc,
        Double hdg) {
}
