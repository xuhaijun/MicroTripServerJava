package com.microtrip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.repository.TrajectoryPointRepository;
import com.microtrip.server.repository.TrajectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轨迹接口「功能完善 + 性能治理」回归测试（2026-09-10）。
 *
 * <p>覆盖本轮服务端演进的<b>对外可见行为</b>，尤其把两个「改了但容易悄悄退化」的点钉死：</p>
 * <ol>
 *   <li><b>列表投影</b>：{@code GET /trajectory/list} 只回摘要字段，
 *       响应体里不得出现 {@code pts} / {@code stops}（否则说明又退回实体查询，
 *       一页 20 条会把数 MB 的 points_json 全读出来）；</li>
 *   <li><b>注销无孤儿</b>：{@code DELETE /auth/account} 必须把 trajectory_points
 *       一并清空 —— 这是「注销即彻底删除」的合规要求，也是本轮修掉的真 bug。</li>
 * </ol>
 *
 * <p>另有新增接口的契约测试：{@code /trajectory/stats}（聚合统计）、
 * {@code /trajectory/sync-batch}（离线批量补传，逐条隔离失败）、
 * {@code /trajectory/list?from=&to=}（时间范围过滤）。</p>
 *
 * <p>配置与 {@link ApiContractTest} 完全一致，以便复用同一个 Spring 上下文（加速测试）。</p>
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
class TrajectoryOptimizationTest {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TrajectoryOptimizationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TrajectoryPointRepository pointRepository;

    @Autowired
    private TrajectoryRepository trajectoryRepository;

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
        Map<String, String> body = Map.of("phone", nextPhone(), "password", "secret1", "nickname", "opt");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/auth/register",
                new HttpEntity<>(body, bearer(null)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readTree(resp.getBody()).path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 token: " + resp.getBody(), e);
        }
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 JSON: " + body, e);
        }
    }

    /** 同步一条轨迹，返回响应体 */
    private JsonNode sync(String token, String id, long start, int pointCount, double distance) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < pointCount; i++) {
            if (i > 0) pts.append(',');
            pts.append("{\"lat\":30.6").append(i).append(",\"lng\":104.0").append(i)
               .append(",\"ts\":").append(start + i * 1000L).append(",\"alt\":500.0}");
        }
        String body = """
                {"trajectory":{"id":"%s","start":%d,"end":%d,"distance":%s,"duration":3600,
                "ascent":100.0,"descent":80.0,"avgSpeed":12.3,"title":"t","city":"成都",
                "pts":[%s]}}""".formatted(id, start, start + 3600_000L, distance, pts);
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(resp.getBody());
    }

    // ==================== 新增：聚合统计 ====================

    @Test
    void stats_emptyAccount_returnsZeros() {
        String token = registerAndGetToken();
        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/stats",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp.getBody());
        assertThat(node.path("count").asLong()).isZero();
        assertThat(node.path("totalPoints").asLong()).isZero();
        assertThat(node.path("totalDistance").asDouble()).isZero();
    }

    @Test
    void stats_aggregatesInSql() {
        String token = registerAndGetToken();
        long t1 = 1_700_000_000_000L;
        long t2 = 1_800_000_000_000L;
        sync(token, "tr_stat_a", t1, 2, 12.5);
        sync(token, "tr_stat_b", t2, 3, 17.5);

        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/stats",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp.getBody());

        assertThat(node.path("count").asLong()).isEqualTo(2);
        assertThat(node.path("totalDistance").asDouble()).isEqualTo(30.0);  // 12.5 + 17.5
        assertThat(node.path("totalDuration").asLong()).isEqualTo(7200);    // 3600 × 2
        assertThat(node.path("maxDistance").asDouble()).isEqualTo(17.5);
        assertThat(node.path("totalAscent").asDouble()).isEqualTo(200.0);
        assertThat(node.path("firstStart").asLong()).isEqualTo(t1);
        assertThat(node.path("lastStart").asLong()).isEqualTo(t2);
        assertThat(node.path("totalPoints").asLong()).isEqualTo(5);         // 2 + 3
    }

    /** 统计接口是字面量路径 /stats，不能被 /{id} 通配吃掉（通配会把 stats 当成轨迹 id） */
    @Test
    void stats_notSwallowedByPathVariable() {
        String token = registerAndGetToken();
        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/stats",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("count");   // 而非 NOT_FOUND「轨迹不存在」
    }

    // ==================== 新增：列表时间范围过滤 ====================

    @Test
    void list_filteredByStartTimeRange() {
        String token = registerAndGetToken();
        long old = 1_700_000_000_000L;   // 2023-11
        long recent = 1_800_000_000_000L; // 2027-01
        sync(token, "tr_range_old", old, 1, 1.0);
        sync(token, "tr_range_new", recent, 1, 2.0);

        ResponseEntity<String> all = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(all.getBody()).path("total").asLong()).isEqualTo(2);

        ResponseEntity<String> ranged = rest.exchange(
                base() + "/api/v1/trajectory/list?from=" + (old + 1_000_000L),
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(ranged.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(ranged.getBody());
        assertThat(node.path("total").asLong()).isEqualTo(1);
        assertThat(node.path("list").get(0).path("id").asText()).isEqualTo("tr_range_new");
    }

    @Test
    void list_fromGreaterThanTo_badRequest() {
        String token = registerAndGetToken();
        ResponseEntity<String> resp = rest.exchange(
                base() + "/api/v1/trajectory/list?from=2000&to=1000",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================== 性能治理：列表不得回传大字段 ====================

    /**
     * 列表接口必须只返回摘要列。
     * 若哪天有人把投影换回实体查询，响应里立刻会出现 {@code pts}，此用例即报警。
     */
    @Test
    void list_returnsSummaryOnly_withoutPointsJson() {
        String token = registerAndGetToken();
        sync(token, "tr_summary_only", 1_700_000_000_000L, 3, 5.0);

        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).contains("tr_summary_only");
        assertThat(body).contains("\"distance\"");
        assertThat(body).doesNotContain("\"pts\"");
        assertThat(body).doesNotContain("\"stops\"");
        assertThat(body).doesNotContain("pointsJson");
    }

    // ==================== 新增：批量同步（逐条隔离失败）====================

    @Test
    void syncBatch_partialFailure_doesNotRollbackOthers() throws Exception {
        String token = registerAndGetToken();

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("id", "tr_batch_ok1", "start", 1_700_000_000_000L,
                "pts", List.of(Map.of("lat", 30.1, "lng", 104.1, "ts", 1_700_000_000_000L),
                               Map.of("lat", 30.2, "lng", 104.2, "ts", 1_700_000_001_000L))));
        // 失败 1：缺 id
        items.add(Map.of("start", 1_700_000_000_000L, "pts", List.of()));
        // 失败 2：id 超过 64 字符列宽 → 落库报错，用于验证「单条失败只回滚自己」
        items.add(Map.of("id", "x".repeat(80), "start", 1_700_000_000_000L, "pts", List.of()));
        items.add(Map.of("id", "tr_batch_ok2", "start", 1_700_000_100_000L,
                "pts", List.of(Map.of("lat", 30.3, "lng", 104.3, "ts", 1_700_000_100_000L))));

        String body = objectMapper.writeValueAsString(Map.of("trajectories", items));
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/trajectory/sync-batch",
                new HttpEntity<>(body, bearer(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp.getBody());
        assertThat(node.path("total").asInt()).isEqualTo(4);
        assertThat(node.path("succeeded").asInt()).isEqualTo(2);
        assertThat(node.path("failed").asInt()).isEqualTo(2);

        // 关键：夹在两条坏数据之间的第 4 条仍然成功落库（逐条独立事务，未整批回滚）
        assertThat(pointRepository.countByTrajectoryId("tr_batch_ok1")).isEqualTo(2L);
        assertThat(pointRepository.countByTrajectoryId("tr_batch_ok2")).isEqualTo(1L);
        assertThat(trajectoryRepository.findById("tr_batch_ok2")).isPresent();
    }

    @Test
    void syncBatch_duplicateIdInSameBatch_reportedAsFailure() throws Exception {
        String token = registerAndGetToken();
        List<Map<String, Object>> items = List.of(
                Map.of("id", "tr_dup", "start", 1L, "pts", List.of()),
                Map.of("id", "tr_dup", "start", 2L, "pts", List.of()));
        String body = objectMapper.writeValueAsString(Map.of("trajectories", items));

        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/trajectory/sync-batch",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp.getBody());
        assertThat(node.path("succeeded").asInt()).isEqualTo(1);
        assertThat(node.path("failed").asInt()).isEqualTo(1);
        assertThat(resp.getBody()).contains("重复");
    }

    @Test
    void syncBatch_emptyOrTooMany_badRequest() throws Exception {
        String token = registerAndGetToken();
        // 空数组
        ResponseEntity<String> empty = rest.postForEntity(base() + "/api/v1/trajectory/sync-batch",
                new HttpEntity<>("{\"trajectories\":[]}", bearer(token)), String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 超过 20 条上限
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(Map.of("id", "tr_many_" + i, "start", 1L, "pts", List.of()));
        }
        String body = objectMapper.writeValueAsString(Map.of("trajectories", many));
        ResponseEntity<String> tooMany = rest.postForEntity(base() + "/api/v1/trajectory/sync-batch",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooMany.getBody()).contains("20");
    }

    // ==================== 同步回执补充落库条数 ====================

    @Test
    void sync_returnsPointsAndStopsCount() {
        String token = registerAndGetToken();
        String body = """
                {"trajectory":{"id":"tr_echo","start":1700000000000,"end":1700003600000,"city":"成都",
                "pts":[{"lat":30.6,"lng":104.0,"ts":1700000000000},
                       {"lat":30.61,"lng":104.01,"ts":1700001000000},
                       {"lat":30.62,"lng":104.02,"ts":1700002000000}],
                "stops":[{"lat":30.6,"lng":104.0,"arr":1700000000000,"dep":1700001000000,"dur":1000000}]}}""";
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp.getBody());
        // 旧字段保持兼容
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("id").asText()).isEqualTo("tr_echo");
        // 新增字段：实际落库条数（超过上限时客户端可据此发现被截断）
        assertThat(node.path("points").asInt()).isEqualTo(3);
        assertThat(node.path("stops").asInt()).isEqualTo(1);
    }

    // ==================== 修复：注销不留孤儿 GPS 点 ====================

    /**
     * 账号注销必须把 {@code trajectory_points} 一并清空。
     *
     * <p>修复前：只有 {@code trajectories} 被删，points 表按 trajectory_id 存的
     * 全部行都成了孤儿 —— 用户已注销、数据却还在库里，既占空间也违背「彻底删除」承诺。</p>
     */
    @Test
    void deleteAccount_purgesOrphanTrajectoryPoints() {
        String token = registerAndGetToken();
        String id = "tr_orphan_check";
        sync(token, id, 1_700_000_000_000L, 4, 9.9);
        assertThat(pointRepository.countByTrajectoryId(id)).isEqualTo(4L);

        ResponseEntity<String> del = rest.exchange(base() + "/api/v1/auth/account",
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 轨迹与 GPS 点都必须为 0，否则就是孤儿数据
        assertThat(trajectoryRepository.findById(id)).isEmpty();
        assertThat(pointRepository.countByTrajectoryId(id)).isZero();
    }

    /** 跨用户隔离：B 看不到 A 的统计与批量结果（归属只认 JWT） */
    @Test
    void stats_isolatedPerUser() {
        String tokenOfA = registerAndGetToken();
        String tokenOfB = registerAndGetToken();
        sync(tokenOfA, "tr_iso_stat", 1_700_000_000_000L, 3, 42.0);

        ResponseEntity<String> respB = rest.exchange(base() + "/api/v1/trajectory/stats",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenOfB)), String.class);
        JsonNode nodeB = json(respB.getBody());
        assertThat(nodeB.path("count").asLong()).isZero();
        assertThat(nodeB.path("totalPoints").asLong()).isZero();
    }

    /**
     * 大批量落库（5000 个 GPS 点）：验证 JDBC 批量写入路径真的走通，且点数一条不少。
     *
     * <p>不做耗时断言（CI 机器性能差异大，时序断言必然成 flaky），
     * 只打印实测耗时供人工比对 —— 若哪天 {@code hibernate.jdbc.batch_size}
     * 被误删，这里的耗时会立刻从「百毫秒级」跳到「数秒级」，日志里一眼可见。</p>
     */
    @Test
    void sync_largeTrajectory_persistsAllPointsViaBatchInsert() {
        String token = registerAndGetToken();
        String id = "tr_large_batch";
        int points = 5000;

        long begin = System.currentTimeMillis();
        JsonNode resp = sync(token, id, 1_700_000_000_000L, points, 123.4);
        long elapsed = System.currentTimeMillis() - begin;

        assertThat(resp.path("points").asInt()).isEqualTo(points);
        assertThat(pointRepository.countByTrajectoryId(id)).isEqualTo(points);
        log.info("[perf] 同步 {} 个 GPS 点落库耗时 {} ms（含 HTTP + JSON 解析 + 批量 INSERT）",
                points, elapsed);
    }
}
