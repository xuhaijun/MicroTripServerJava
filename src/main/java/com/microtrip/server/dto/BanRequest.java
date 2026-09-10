package com.microtrip.server.dto;

/**
 * 封禁/解封请求体：{ userId, ttlSeconds? }
 * ttlSeconds 缺省为 0，表示长期封禁。
 */
public record BanRequest(String userId, Long ttlSeconds) {
}
