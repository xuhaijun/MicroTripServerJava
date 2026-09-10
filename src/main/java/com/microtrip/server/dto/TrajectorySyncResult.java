package com.microtrip.server.dto;

/**
 * 单条轨迹同步结果（内部返回值，也用于批量同步的逐条回执）。
 *
 * @param id     轨迹 ID
 * @param points 实际落库的 GPS 点数（若请求点数超过服务端上限 20000，此处会小于请求条数，
 *               客户端可据此判断是否被截断）
 * @param stops  实际落库的停留点数（上限 500）
 */
public record TrajectorySyncResult(String id, int points, int stops) {
}
