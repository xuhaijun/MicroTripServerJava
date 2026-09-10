package com.microtrip.server.dto;

import java.util.List;

/**
 * 批量同步请求体：{ trajectories: [ {...}, {...} ] }。
 *
 * <p>用于 App 端「离线期间录了多条轨迹，联网后一次性补传」：
 * 单次最多 {@code 20} 条（服务端校验），逐条隔离失败 —— 其中一条数据有问题
 * 不会让其余轨迹一起回滚。</p>
 */
public record TrajectorySyncBatchRequest(List<TrajectorySyncDto> trajectories) {
}
