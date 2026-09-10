package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 停留点（轨迹 stops 元素）。
 * { lat, lng, arr, dep, dur, rad, label?, addr? }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectoryStopDto(
        Double lat,
        Double lng,
        Long arr,
        Long dep,
        Long dur,
        Double rad,
        String label,
        String addr) {
}
