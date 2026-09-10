package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 景点项（数据源 data/scenery.json）。
 * { id, name, image, rating, ticket, duration, openTime, tag, tags[],
 *   desc, address, latitude, longitude }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SceneryItem(
        int id,
        String name,
        String image,
        double rating,
        String ticket,
        String duration,
        String openTime,
        String tag,
        List<String> tags,
        String desc,
        String address,
        double latitude,
        double longitude) {
}
