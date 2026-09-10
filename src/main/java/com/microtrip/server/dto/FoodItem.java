package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 美食项（数据源 data/food.json）。
 * { id, name, image, rating, price, tag, tags[], desc, tips }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodItem(
        int id,
        String name,
        String image,
        double rating,
        String price,
        String tag,
        List<String> tags,
        String desc,
        String tips) {
}
