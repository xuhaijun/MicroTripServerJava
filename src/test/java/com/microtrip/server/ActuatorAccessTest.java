package com.microtrip.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator 端点访问控制的集成测试（走完整安全过滤链）。
 *
 * <p><b>要守住的属性：</b>健康/信息类端点必须公开（探活/编排依赖），
 * 而 metrics / prometheus 这类会暴露 JVM 堆、连接池等待数、接口耗时分布的端点，
 * 只允许白名单来源 IP 访问。</p>
 *
 * <p><b>为什么用 MockMvc 而不是真实 HTTP：</b>MockMvc 能通过
 * {@code request.setRemoteAddr(...)} 伪造来源地址，从而真正验证「外网 IP 被拒」。
 * 真实 HTTP 测试里来源永远是 127.0.0.1，只能验证放行路径，
 * 拒绝路径根本测不到 —— 那样测试是自欺欺人的。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "microtrip.jwt.secret=fixed-test-secret-please-make-it-long-enough-1234567890ABC",
        "spring.datasource.url=jdbc:h2:mem:testactuator;DB_CLOSE_DELAY=-1",
        "microtrip.ratelimit.enabled=false",
        "microtrip.admin.bootstrap.phone=",
        // 固定白名单，避免依赖运行环境的默认值
        "microtrip.security.actuator-allow-list=127.0.0.1,::1",
        // ---------- 必须显式打开，否则本测试测不到东西 ----------
        // Spring Boot 的测试支持会向环境注入一个名为 test 的属性源，内容为：
        //   management.defaults.metrics.export.enabled=false
        //   management.simple.metrics.export.enabled=true
        //   management.tracing.enabled=false
        // 即**在 @SpringBootTest 中刻意关闭所有非 simple 的指标导出器**，避免测试把指标
        // 推送到 Prometheus 等外部系统。副作用是 PrometheusMeterRegistry 不存在，
        // /actuator/prometheus 端点也就不会注册（返回 404），
        // 于是「白名单放行」这条断言会失败 —— 而它失败的原因是框架机制，不是我们的安全逻辑。
        // 因此在测试里显式把 prometheus 导出打开，让端点真实存在，断言才有意义。
        "management.prometheus.metrics.export.enabled=true"
})
class ActuatorAccessTest {

    /** 公网测试网段（RFC 5737 保留地址，永远不会是真实内网） */
    private static final String EXTERNAL_IP = "203.0.113.7";

    @Autowired
    private MockMvc mockMvc;

    private MockHttpServletRequestBuilder fromExternal(String url) {
        return get(url).with(request -> {
            request.setRemoteAddr(EXTERNAL_IP);
            return request;
        });
    }

    // ============================================================
    // 1. 公开端点：任何来源都应放行
    // ============================================================

    @Test
    @DisplayName("健康检查对任意来源公开（本机）")
    void healthPublicFromLoopback() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("健康检查对任意来源公开（外网 IP 也放行）—— 探活不能被 IP 白名单挡住")
    void healthPublicFromExternal() throws Exception {
        mockMvc.perform(fromExternal("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("liveness 子端点公开 —— 容器 HEALTHCHECK 依赖它")
    void livenessPublicFromExternal() throws Exception {
        mockMvc.perform(fromExternal("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("info 端点公开")
    void infoPublicFromExternal() throws Exception {
        mockMvc.perform(fromExternal("/actuator/info"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // 2. 指标端点：本机放行
    // ============================================================

    @Test
    @DisplayName("本机可访问 metrics（Micrometer 指标列表）")
    void metricsAllowedFromLoopback() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("本机可访问 prometheus（拉取格式指标）")
    void prometheusAllowedFromLoopback() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    // ============================================================
    // 3. 指标端点：外网来源一律拒绝（本测试的核心断言）
    // ============================================================

    @Test
    @DisplayName("外网来源访问 metrics 被拒 —— 不泄露 JVM 与连接池内部数据")
    void metricsDeniedFromExternal() throws Exception {
        mockMvc.perform(fromExternal("/actuator/metrics"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("外网来源访问 prometheus 被拒")
    void prometheusDeniedFromExternal() throws Exception {
        mockMvc.perform(fromExternal("/actuator/prometheus"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("伪造 X-Forwarded-For 无法绕过白名单 —— 判定只认 TCP 来源地址")
    void spoofedForwardedHeaderCannotBypass() throws Exception {
        // 这是 IP 白名单最经典的漏洞：如果实现里信任了 X-Forwarded-For，
        // 攻击者只要加一个该头就能冒充内网地址。判定必须只看 getRemoteAddr()。
        mockMvc.perform(fromExternal("/actuator/prometheus")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .header("X-Real-IP", "127.0.0.1")
                        .header("Forwarded", "for=127.0.0.1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("不存在的 Actuator 路径与真实端点的拒绝响应一致 —— 不泄露端点是否存在")
    void unknownActuatorPathIndistinguishable() throws Exception {
        int real = mockMvc.perform(fromExternal("/actuator/prometheus"))
                .andReturn().getResponse().getStatus();
        int fake = mockMvc.perform(fromExternal("/actuator/this-endpoint-does-not-exist"))
                .andReturn().getResponse().getStatus();

        // 两者必须相同：否则攻击者可以通过状态码差异枚举出哪些端点真实存在
        org.assertj.core.api.Assertions.assertThat(fake).isEqualTo(real);
    }

    // ============================================================
    // 4. 业务接口不受影响（回归保护）
    // ============================================================

    @Test
    @DisplayName("业务受保护接口仍是 401 —— Actuator 改造未波及既有鉴权链")
    void businessEndpointStillUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/trajectory/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("公开静态数据接口仍可访问 —— 未被 Actuator 规则误伤")
    void publicStaticDataStillAccessible() throws Exception {
        mockMvc.perform(get("/api/v1/scenery/list").param("city", "成都"))
                .andExpect(status().isOk());
    }
}
