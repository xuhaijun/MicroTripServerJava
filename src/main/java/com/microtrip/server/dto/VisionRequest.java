package com.microtrip.server.dto;

/**
 * 拍照识物请求体：{ imageBase64, city? }
 */
public record VisionRequest(String imageBase64, String city) {
}
