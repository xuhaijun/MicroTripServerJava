package com.microtrip.server.dto;

import java.util.List;

/**
 * 拍照识物结果：
 * { labels: [...], matches: { scenery: [...], food: [...] } }
 */
public record VisionResult(List<VisionLabel> labels, Matches matches) {

    public record Matches(List<SceneryItem> scenery, List<FoodItem> food) {
    }
}
