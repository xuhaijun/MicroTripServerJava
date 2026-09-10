package com.microtrip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /trajectory/stats} 的<b>客户端契约测试</b>（2026-09-10 补充）。
 *
 * <h2>为什么需要这个测试</h2>
 * 客户端 {@code CloudStats.fromMap}（见客户端仓库 {@code lib/models/cloud_stats.dart}）是
 * <b>宽容解析</b>：按字段名取值，取不到就降级为 0，<b>绝不抛异常</b>。这个设计对稳定性是对的，
 * 但副作用是 —— 服务端一旦把 {@code totalDistance} 改名、或把数值改成字符串，
 * <b>App 不会报错，只会静默显示 0</b>。卡片照样渲染、界面毫无异常，故障极难被发现。
 *
 * <p>因此本测试把「客户端认识的字段名 + 各自的 JSON 类型」钉死在服务端侧：
 * 谁改了字段名/类型/可空性，{@code mvn test} 立刻变红，不需要人工跑一遍模拟器才能察觉。</p>
 *
 * <h2>为什么不与客户端共享常量</h2>
 * 字段清单在此处是<b>刻意复制的字面量</b>：契约测试的价值来自「两端各自独立声明」。
 * 若共享同一份常量，改名时两边会一起改，反而测不出任何问题。
 *
 * <p>本类的前身是手工脚本 {@code scripts/verify_stats_contract.py}；
 * 脚本保留用于对真实运行实例做端到端复现，自动化的契约守护以本类为准。</p>
 *
 * <p>配置与 {@link ApiContractTest} / {@link TrajectoryOptimizationTest} 完全一致，
 * 以便复用同一个 Spring 上下文（加速测试）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "microtrip.jwt.secret=fixed-test-secret-please-make-it-long-enough-1234567890ABC",
        "spring.datasource.url=jdbc:h2:mem:testmicrotrip;DB_CLOSE_DELAY=-1",
        "microtrip.vision.provider=tencent",
        "microtrip.ratelimit.enabled=false",
        "microtrip.admin.bootstrap.phone=19900000001",
        "microtrip.admin.bootstrap.password=admin-secret-123",
        "microtrip.admin.bootstrap.nickname=测试管理员"
})
class StatsClientContractTest {

    // ==================== 契约声明（改这里之前请先改客户端） ====================

    /**
     * 客户端 {@code CloudStats.fromMap} 会读取的全部字段名。
     *
     * <p>与 {@code lib/models/cloud_stats.dart} 逐字对应，11 个。
     * <b>服务端任何字段改名都必须同步更新客户端，否则卡片静默显示 0。</b></p>
     */
    private static final Set<String> CLIENT_KEYS = Set.of(
            "count",
            "totalDistance",
            "totalDuration",
            "totalAscent",
            "totalDescent",
            "maxDistance",
            "maxAvgSpeed",
            "firstStart",
            "lastStart",
            "lastSyncedAt",
            "totalPoints");

    /**
     * 无数据时允许被省略的字段（{@code @JsonInclude(NON_NULL)} 行为）。
     *
     * <p>这 5 个都是「没有数据就没有值」的语义，客户端对 null/缺失已有兜底；
     * 其余 6 个（count / totalDistance / totalDuration / totalAscent /
     * totalDescent / totalPoints）<b>必须永远存在</b> —— 它们是卡片的主展示指标，
     * 缺失会直接让界面出现空白而非 0。</p>
     */
    private static final Set<String> NULLABLE_WHEN_EMPTY = Set.of(
            "maxDistance", "maxAvgSpeed", "firstStart", "lastStart", "lastSyncedAt");

    /**
     * 服务端刻意多返回、客户端会直接忽略的字段白名单。
     *
     * <p>当前为空。若服务端确实需要新增字段，请把字段名加到这里 ——
     * 这一步是<b>刻意的摩擦</b>，提醒你确认客户端是否需要消费它。</p>
     */
    private static final Set<String> SERVER_ONLY_ALLOWLIST = Set.of();

    // ==================== 基础设施 ====================

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @BeforeEach
    void setUp() {
        rest.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(
                        org.apache.hc.client5.http.impl.classic.HttpClients.custom().build()));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String nextPhone() {
        return "13" + String.format("%09d", SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    private String registerAndGetToken() {
        Map<String, String> body = Map.of("phone", nextPhone(), "password", "secret1", "nickname", "contract");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/auth/register",
                new HttpEntity<>(body, bearer(null)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(resp.getBody()).path("token").asText();
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 JSON: " + body, e);
        }
    }

    private JsonNode stats(String token) {
        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/stats",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(resp.getBody());
    }

    /**
     * 同步一条轨迹。
     *
     * <p>GPS 点数组的键固定为 {@code pts} —— 这是 {@code TrajectorySyncDto} 的字段名，
     * 也是客户端 {@code TrajectoryRecord.toMap} 的包装键，两侧必须一致。</p>
     */
    private JsonNode sync(String token, String id, long start, int pointCount, double distance, Double avgSpeed) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < pointCount; i++) {
            if (i > 0) pts.append(',');
            pts.append("{\"lat\":30.6").append(i).append(",\"lng\":104.0").append(i)
               .append(",\"ts\":").append(start + i * 1000L).append('}');
        }
        String speed = avgSpeed == null ? "null" : avgSpeed.toString();
        String body = """
                {"trajectory":{"id":"%s","start":%d,"end":%d,"distance":%s,"duration":3600,
                "ascent":100.0,"descent":80.0,"avgSpeed":%s,"title":"t","city":"成都",
                "pts":[%s]}}""".formatted(id, start, start + 3600_000L, distance, speed, pts);
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(resp.getBody());
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ==================== 1. 字段名集合契约 ====================

    @Test
    @DisplayName("有数据时：11 个客户端字段全部存在，且不出现白名单外的陌生字段")
    void stats_withData_fieldNamesMatchClientContract() {
        String token = registerAndGetToken();
        sync(token, "tr_contract_a", 1_700_000_000_000L, 2, 12.5, 12.3);
        sync(token, "tr_contract_b", 1_800_000_000_000L, 3, 17.5, 15.5);

        JsonNode node = stats(token);
        Set<String> serverKeys = keysOf(node);

        // 危险方向：客户端要读的字段缺失（改名/删除）→ 卡片静默显示 0
        assertThat(serverKeys)
                .as("客户端 CloudStats.fromMap 需要这些字段；缺失会让「我的」页卡片静默显示 0，"
                        + "而不会报错。若服务端刚改过字段名，请同步改 lib/models/cloud_stats.dart。"
                        + "\n服务端实际返回: %s", serverKeys)
                .containsAll(CLIENT_KEYS);

        // 反向：出现陌生字段说明服务端在没有同步客户端的情况下扩展了响应，
        // 这里刻意报错以形成一次「有意识的对齐」
        Set<String> unexpected = new LinkedHashSet<>(serverKeys);
        unexpected.removeAll(CLIENT_KEYS);
        unexpected.removeAll(SERVER_ONLY_ALLOWLIST);
        assertThat(unexpected)
                .as("服务端多返回了客户端契约之外的字段 %s。"
                        + "若这是刻意新增，请把字段名加入 SERVER_ONLY_ALLOWLIST 并评估客户端是否要消费。", unexpected)
                .isEmpty();
    }

    // ==================== 2. JSON 类型契约 ====================

    @Test
    @DisplayName("有数据时：所有字段必须是 JSON 数字，不能是字符串")
    void stats_allValuesAreJsonNumbers() {
        String token = registerAndGetToken();
        sync(token, "tr_type_check", 1_700_000_000_000L, 2, 12.5, 12.3);

        JsonNode node = stats(token);
        for (String key : CLIENT_KEYS) {
            JsonNode value = node.path(key);
            assertThat(value.isMissingNode())
                    .as("字段 %s 缺失（客户端会退化为 0）", key)
                    .isFalse();
            // 数值若被序列化成字符串（例如 "12.5"），Dart 侧 num 转换同样会静默退化为 0，
            // 因此这里必须把「是数字」也当作契约的一部分钉住
            assertThat(value.isNumber())
                    .as("字段 %s 的类型应为 JSON 数字，实际为 %s", key, value.getNodeType())
                    .isTrue();
        }
    }

    // ==================== 3. 空数据的 NON_NULL 省略行为 ====================

    @Test
    @DisplayName("无数据时：主指标字段必须存在且为 0，可空字段按 NON_NULL 省略")
    void stats_emptyAccount_omitsNullableKeysButKeepsZeros() {
        String token = registerAndGetToken();
        JsonNode node = stats(token);

        // 主展示指标：卡片直接展示，缺失会显示空白而非 0
        Set<String> mustExist = new LinkedHashSet<>(CLIENT_KEYS);
        mustExist.removeAll(NULLABLE_WHEN_EMPTY);
        assertThat(keysOf(node))
                .as("无数据时这些字段仍必须出现（客户端卡片主指标）")
                .containsAll(mustExist);
        for (String key : mustExist) {
            assertThat(node.path(key).asDouble())
                    .as("无数据时 %s 应为 0", key)
                    .isZero();
        }

        // 可空字段：@JsonInclude(NON_NULL) 会省略，客户端对此已有兜底（读成 null → 不展示）
        assertThat(keysOf(node))
                .as("这 5 个字段在无数据时应被 NON_NULL 省略；若开始返回 null 字面量，"
                        + "说明 DTO 上的 @JsonInclude 被改动了")
                .doesNotContainAnyElementsOf(NULLABLE_WHEN_EMPTY);
    }

    // ==================== 4. 聚合值取自真实落库数据 ====================

    @Test
    @DisplayName("聚合值正确：含 maxAvgSpeed（说明 avgSpeed 真的落库了）")
    void stats_aggregatesIncludingMaxAvgSpeed() {
        String token = registerAndGetToken();
        sync(token, "tr_agg_a", 1_700_000_000_000L, 2, 12.5, 12.3);
        sync(token, "tr_agg_b", 1_800_000_000_000L, 3, 17.5, 15.5);

        JsonNode node = stats(token);
        assertThat(node.path("count").asLong()).isEqualTo(2);
        assertThat(node.path("totalDistance").asDouble()).isEqualTo(30.0);
        assertThat(node.path("totalDuration").asLong()).isEqualTo(7200);
        assertThat(node.path("totalAscent").asDouble()).isEqualTo(200.0);
        assertThat(node.path("totalDescent").asDouble()).isEqualTo(160.0);
        assertThat(node.path("maxDistance").asDouble()).isEqualTo(17.5);
        // maxAvgSpeed 依赖 avg_speed 列真的被 sync 写入了值（DTO 用 @JsonAlias("avgSpd") 兼容旧字段）
        assertThat(node.path("maxAvgSpeed").asDouble()).isEqualTo(15.5);
        assertThat(node.path("totalPoints").asLong()).isEqualTo(5);
    }

    // ==================== 5. 钉住「pts 键名」这个坑 ====================

    /**
     * GPS 点数组必须用 {@code pts} 键。
     *
     * <p>传成 {@code points} 或 {@code locations} <b>不会报错</b>，服务端只是安静地存 0 个点，
     * 回执里 {@code points = 0} —— 客户端若只看 HTTP 200 就会以为同步成功。
     * 这是这类接口最阴的坑（本轮联调时真实踩到过），此处固化现状：</p>
     * <ul>
     *   <li>用 {@code pts} → 回执 points = 落库条数，> 0；</li>
     *   <li>用 {@code points} → 回执 points = 0（客户端必须依赖该字段做自检）。</li>
     * </ul>
     *
     * <p>若将来服务端决定新增 {@code points} 别名，请同步更新客户端 {@code toMap}
     * 并调整本用例 —— 别默默改掉，否则「静默丢点」会重新变得不可见。</p>
     */
    @Test
    @DisplayName("GPS 点键名必须是 pts：传 points 会静默落库 0 点（历史坑，固化现状）")
    void sync_gpsPointKeyMustBePts_wrongKeySilentlyStoresNothing() {
        String token = registerAndGetToken();

        String correct = """
                {"trajectory":{"id":"tr_key_ok","start":1700000000000,"city":"成都",
                "pts":[{"lat":30.6,"lng":104.0,"ts":1700000000000},
                       {"lat":30.61,"lng":104.01,"ts":1700000001000}]}}""";
        ResponseEntity<String> okResp = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(correct, bearer(token)), String.class);
        assertThat(okResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(okResp.getBody()).path("points").asInt()).isEqualTo(2);

        String wrongKey = """
                {"trajectory":{"id":"tr_key_bad","start":1700000000000,"city":"成都",
                "points":[{"lat":30.6,"lng":104.0,"ts":1700000000000},
                          {"lat":30.61,"lng":104.01,"ts":1700000001000}]}}""";
        ResponseEntity<String> badResp = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(wrongKey, bearer(token)), String.class);
        // 注意：HTTP 仍是 200，不会报错 —— 所以客户端必须读回执里的 points 自检
        assertThat(badResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(badResp.getBody()).path("points").asInt())
                .as("服务端只认 pts；若这里不再是 0，说明新增了键名别名，请同步更新客户端与本文档")
                .isZero();
    }

    // ==================== 6. 跨用户隔离（契约层） ====================

    @Test
    @DisplayName("别人的轨迹不会进入我的统计（归属只认 JWT）")
    void stats_doesNotLeakOtherUsersData() {
        String tokenOfA = registerAndGetToken();
        String tokenOfB = registerAndGetToken();
        sync(tokenOfA, "tr_leak_check", 1_700_000_000_000L, 3, 42.0, 9.9);

        JsonNode nodeB = stats(tokenOfB);
        assertThat(nodeB.path("count").asLong()).isZero();
        assertThat(nodeB.path("totalPoints").asLong()).isZero();
        // 空账号同样必须满足「主指标存在」的契约
        List<String> present = new ArrayList<>();
        nodeB.fieldNames().forEachRemaining(present::add);
        assertThat(present).contains("count", "totalDistance", "totalPoints");
    }
}
