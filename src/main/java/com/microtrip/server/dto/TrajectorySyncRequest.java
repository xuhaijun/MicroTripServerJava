package com.microtrip.server.dto;

/**
 * 轨迹同步请求体：{ trajectory: {...} }
 */
public record TrajectorySyncRequest(TrajectorySyncDto trajectory) {
}
