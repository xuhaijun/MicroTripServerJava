package com.microtrip.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 批量同步响应：{ ok, total, succeeded, failed, results: [{ id, ok, points, error }] }。
 *
 * <p>{@code ok} 语义为「请求本身被受理」（HTTP 200），不等价于「全部成功」；
 * 逐条成败请看 {@code results}。即便有 {@code failed > 0}，已成功的轨迹
 * 也已经<b>真实落库</b>（每条独立事务），客户端只需重试失败项。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrajectorySyncBatchResponse(
        boolean ok,
        int total,
        int succeeded,
        int failed,
        List<Item> results) {

    /** 单条回执 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, boolean ok, Integer points, String error) {

        /** 成功回执（供 service 层构造） */
        public static Item success(String id, int points) {
            return new Item(id, true, points, null);
        }

        /** 失败回执（供 service 层构造） */
        public static Item failure(String id, String error) {
            return new Item(id, false, null, error);
        }
    }
}
