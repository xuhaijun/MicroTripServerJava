package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 推荐门店（数据源 data/shops.json）。
 * { id, name, rating, distance, avgPrice, address, latitude, longitude }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodShop(
        int id,
        String name,
        double rating,
        String distance,
        String avgPrice,
        String address,
        double latitude,
        double longitude) {
}
