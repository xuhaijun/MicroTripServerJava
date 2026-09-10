package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 图像识别标签：{ name, confidence(0-1), category }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VisionLabel(String name, double confidence, String category) {
}
