package com.microtrip.server;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流专项测试（独立上下文，低容量）：登录接口在窗口内超阈值返回 429。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "microtrip.jwt.secret=fixed-test-secret-please-make-it-long-enough-1234567890ABC",
        "spring.datasource.url=jdbc:h2:mem:ratelimit;DB_CLOSE_DELAY=-1",
        "microtrip.vision.provider=tencent",
        "microtrip.ratelimit.enabled=true",
        "microtrip.ratelimit.capacity=3",
        "microtrip.ratelimit.window-seconds=5"
})
class RateLimitTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        rest.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(
                        org.apache.hc.client5.http.impl.classic.HttpClients.custom().build()));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void auth_login_rateLimited_returnsTooManyRequests() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // 错误密码 → 命中限流路径 /api/v1/auth/login，未超限时返回 401
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> r = rest.postForEntity(base() + "/api/v1/auth/login",
                    new HttpEntity<>(Map.of("phone", "13800000000", "password", "x"), h), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // 第 4 次超窗口容量 → 429
        ResponseEntity<String> blocked = rest.postForEntity(base() + "/api/v1/auth/login",
                new HttpEntity<>(Map.of("phone", "13800000000", "password", "x"), h), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS); // 429
        assertThat(blocked.getBody()).contains("RATE_LIMITED");
    }
}
