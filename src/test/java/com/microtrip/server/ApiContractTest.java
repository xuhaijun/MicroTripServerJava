package com.microtrip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.repository.TrajectoryPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口契约 / 集成测试（Spring Boot Test，随机端口 + 真实过滤链）。
 *
 * <p>覆盖：健康检查、注册/登录、鉴权（401）、轨迹全链路（同步/列表/详情/删除/跨用户隔离）、
 * 拍照识物（501/400）、主动登出吊销（401）、角色化封禁（403）、路由 404 / 方法 405，
 * 以及本轮演进：<b>接口版本化 v1</b>、<b>trajectory_points 拆表</b>、<b>旧路径向后兼容</b>。</p>
 *
 * <p>配置：内存 H2（避免文件锁）、固定 JWT 密钥（确定性）、腾讯云未配置（走 501）、
 * 启动时注入测试管理员（角色化封禁）、关闭限流（避免干扰其他用例）。</p>
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
class ApiContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TrajectoryPointRepository pointRepository;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    /**
     * 用 Apache HttpClient 替换 JDK {@code HttpURLConnection}：后者在「POST 带 body 且服务端返回 401」
     * 时会抛 {@code HttpRetryException} 而非返回响应，导致无法断言 401。
     */
    @BeforeEach
    void setUp() {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(
                        org.apache.hc.client5.http.impl.classic.HttpClients.custom().build());
        rest.getRestTemplate().setRequestFactory(factory);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String nextPhone() {
        // 11 位，以 13 开头，满足 ^1\d{10}$
        return "13" + String.format("%09d", SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> doRegister(String phone, String password, String nickname) {
        Map<String, String> body = Map.of("phone", phone, "password", password, "nickname", nickname);
        return rest.postForEntity(base() + "/api/v1/auth/register", new HttpEntity<>(body, bearer(null)), String.class);
    }

    private String registerAndGetToken(String phone, String password, String nickname) {
        ResponseEntity<String> resp = doRegister(phone, password, nickname);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return readToken(resp.getBody());
    }

    private String readToken(String json) {
        try {
            return objectMapper.readTree(json).path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 token: " + json, e);
        }
    }

    private String readUserId(String json) {
        try {
            return objectMapper.readTree(json).path("user").path("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 userId: " + json, e);
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 JSON: " + json, e);
        }
    }

    // ---------------- 测试 ----------------

    @Test
    void health_returnsOk() {
        ResponseEntity<String> resp = rest.getForEntity(base() + "/api/v1/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void register_returnsTokenAndUser() {
        String phone = nextPhone();
        ResponseEntity<String> resp = doRegister(phone, "secret1", "alice");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode user = parse(resp.getBody()).path("user");
        assertThat(resp.getBody()).contains("token");
        assertThat(user.path("phone").asText()).isEqualTo(phone);
        assertThat(user.path("id").asText()).startsWith("u_");
    }

    @Test
    void register_duplicatePhone_conflict() {
        String phone = nextPhone();
        registerAndGetToken(phone, "secret1", "alice");
        ResponseEntity<String> resp = doRegister(phone, "secret1", "bob");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void register_invalidPhone_badRequest() {
        ResponseEntity<String> resp = doRegister("123", "secret1", "alice");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void login_returnsToken_and_wrongPassword_unauthorized() {
        String phone = nextPhone();
        registerAndGetToken(phone, "secret1", "alice");
        // 正确密码
        Map<String, String> ok = Map.of("phone", phone, "password", "secret1");
        ResponseEntity<String> r1 = rest.postForEntity(base() + "/api/v1/auth/login",
                new HttpEntity<>(ok, bearer(null)), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r1.getBody()).contains("token");
        // 错误密码 → 401
        Map<String, String> bad = Map.of("phone", phone, "password", "wrong");
        ResponseEntity<String> r2 = rest.postForEntity(base() + "/api/v1/auth/login",
                new HttpEntity<>(bad, bearer(null)), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_withoutToken_unauthorized() {
        ResponseEntity<String> resp = rest.getForEntity(base() + "/api/v1/trajectory/list", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void trajectory_fullLifecycle_syncListDetailDelete() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        String syncBody = """
                {"trajectory":{"id":"tr_lifecycle_1","start":1700000000000,"end":1700003600000,"distance":12.5,
                "duration":3600000,"ascent":100.0,"descent":80.0,"avgSpeed":12.3,
                "title":"测试轨迹","city":"成都",
                "pts":[{"lat":30.6,"lng":104.0,"ts":1700000000000,"alt":500.0,"spd":3.0}],
                "stops":[{"lat":30.6,"lng":104.0,"arr":1700000000000,"dep":1700001000000,"dur":1000000,"rad":50.0,"label":"起点"}]}}""";

        ResponseEntity<String> sync = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(syncBody, bearer(token)), String.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = parse(sync.getBody()).path("id").asText();
        assertThat(id).isEqualTo("tr_lifecycle_1");

        ResponseEntity<String> list = rest.exchange(base() + "/api/v1/trajectory/list?page=1&pageSize=20",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains(id);

        ResponseEntity<String> detail = rest.exchange(base() + "/api/v1/trajectory/" + id,
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).contains("trajectory");

        ResponseEntity<String> del = rest.exchange(base() + "/api/v1/trajectory/" + id,
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> after = rest.exchange(base() + "/api/v1/trajectory/" + id,
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void trajectory_crossUserIsolation_returnsNotFound() {
        String tokenA = registerAndGetToken(nextPhone(), "secret1", "alice");
        String tokenB = registerAndGetToken(nextPhone(), "secret1", "bob");
        String syncBody = """
                {"trajectory":{"id":"tr_iso_1","start":1700000000000,"end":1700003600000,"city":"成都",
                "pts":[{"lat":30.6,"lng":104.0,"ts":1700000000000}]}}""";
        ResponseEntity<String> sync = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(syncBody, bearer(tokenA)), String.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = parse(sync.getBody()).path("id").asText();

        // 用户 B 访问 A 的轨迹 → 404
        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/" + id,
                HttpMethod.GET, new HttpEntity<>(bearer(tokenB)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void vision_withoutConfig_notConfigured() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        Map<String, String> body = Map.of("imageBase64", "iVBORw0KGgo=", "city", "成都");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/vision/recognize",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED); // 501
        assertThat(resp.getBody()).contains("NOT_CONFIGURED");
    }

    @Test
    void vision_missingImage_badRequest() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        Map<String, String> body = Map.of("city", "成都");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/vision/recognize",
                new HttpEntity<>(body, bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void logout_revokesToken_thenUnauthorized() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        ResponseEntity<String> before = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> logout = rest.postForEntity(base() + "/api/v1/auth/logout",
                new HttpEntity<>(bearer(token)), String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> after = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 注销账号：令牌立即失效 + 账号真正删除（不能再登录） */
    @Test
    void deleteAccount_removesUser_and_revokesToken() {
        String phone = nextPhone();
        String token = registerAndGetToken(phone, "secret1", "alice");

        // 注销前：令牌可用
        ResponseEntity<String> before = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 注销（需 Bearer）
        ResponseEntity<String> del = rest.exchange(base() + "/api/v1/auth/account",
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(del.getBody()).contains("true");

        // 当前令牌已被吊销 → 401
        ResponseEntity<String> after = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 账号已删除 → 原手机号无法再登录
        ResponseEntity<String> relogin = rest.postForEntity(base() + "/api/v1/auth/login",
                new HttpEntity<>(Map.of("phone", phone, "password", "secret1"), bearer(null)),
                String.class);
        assertThat(relogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 注销接口未携带令牌 → 401（不泄露账号是否存在） */
    @Test
    void deleteAccount_withoutToken_unauthorized() {
        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/auth/account",
                HttpMethod.DELETE, new HttpEntity<>(bearer(null)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void banUser_withAdminRole_thenForbidden_and_unbanRestores() {
        // 受害者（普通用户）
        ResponseEntity<String> reg = doRegister(nextPhone(), "secret1", "alice");
        String tokenV = readToken(reg.getBody());
        String userIdV = readUserId(reg.getBody());

        // 管理员（角色化封禁：JWT role=ADMIN，由启动 bootstrap 注入）
        Map<String, String> adminLogin = Map.of(
                "phone", "19900000001", "password", "admin-secret-123");
        ResponseEntity<String> adminResp = rest.postForEntity(base() + "/api/v1/auth/login",
                new HttpEntity<>(adminLogin, bearer(null)), String.class);
        assertThat(adminResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenA = readToken(adminResp.getBody());

        ResponseEntity<String> ban = rest.postForEntity(base() + "/api/v1/admin/ban",
                new HttpEntity<>(Map.of("userId", userIdV, "ttlSeconds", 60), bearer(tokenA)), String.class);
        assertThat(ban.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> blocked = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenV)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> unban = rest.postForEntity(base() + "/api/v1/admin/unban",
                new HttpEntity<>(Map.of("userId", userIdV), bearer(tokenA)), String.class);
        assertThat(unban.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> restored = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(tokenV)), String.class);
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void admin_endpoint_withoutAdminRole_forbidden() {
        // 普通用户令牌调用管理接口 → 403（@PreAuthorize("hasRole('ADMIN')")）
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        ResponseEntity<String> resp = rest.postForEntity(base() + "/api/v1/admin/ban",
                new HttpEntity<>(Map.of("userId", "u_x", "ttlSeconds", 60), bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownRoute_returnsNotFound() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        ResponseEntity<String> resp = rest.exchange(base() + "/no-such-route",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void wrongMethod_returnsMethodNotAllowed() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        // /trajectory/list 仅支持 GET
        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/trajectory/list",
                HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED); // 405
    }

    @Test
    void legacyPaths_backwardCompatible_stillWork() {
        // 旧裸路径（无 /api/v1）仍应可用，保证 Flutter 端零改动
        String phone = nextPhone();
        ResponseEntity<String> reg = rest.postForEntity(base() + "/auth/register",
                new HttpEntity<>(Map.of("phone", phone, "password", "secret1", "nickname", "legacy"),
                        bearer(null)), String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = readToken(reg.getBody());

        ResponseEntity<String> list = rest.exchange(base() + "/trajectory/list",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void trajectory_pointsTable_persistedOnSync_and_cascadeDeleted() {
        String token = registerAndGetToken(nextPhone(), "secret1", "alice");
        String id = "tr_points_1";
        String syncBody = """
                {"trajectory":{"id":"%s","start":1700000000000,"end":1700003600000,"city":"成都",
                "pts":[{"lat":30.6,"lng":104.0,"ts":1700000000000,"alt":500.0,"spd":3.0},
                       {"lat":30.61,"lng":104.01,"ts":1700001000000,"alt":510.0},
                       {"lat":30.62,"lng":104.02,"ts":1700002000000}]}}""".formatted(id);
        ResponseEntity<String> sync = rest.postForEntity(base() + "/api/v1/trajectory/sync",
                new HttpEntity<>(syncBody, bearer(token)), String.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 拆表：3 个点已落库
        assertThat(pointRepository.countByTrajectoryId(id)).isEqualTo(3L);

        // 区域内检索（读侧优化）：矩形应覆盖前 2 个点
        assertThat(pointRepository
                .findByTrajectoryIdAndLatBetweenAndLngBetweenOrderBySeqAsc(id, 30.599, 30.611, 103.999, 104.011)
                .size()).isEqualTo(2);

        // 删除轨迹 → 级联清理 points 表
        rest.exchange(base() + "/api/v1/trajectory/" + id,
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        assertThat(pointRepository.countByTrajectoryId(id)).isEqualTo(0L);
    }
}
