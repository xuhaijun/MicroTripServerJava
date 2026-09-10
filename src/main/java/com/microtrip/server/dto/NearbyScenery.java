package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 周边推荐景点（排除自身，按真实距离取前 4）。
 * { id, name, image, rating, distance }（distance 形如 "1.5km" / "800m"）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NearbyScenery(
        int id,
        String name,
        String image,
        double rating,
        String distance) {
}
