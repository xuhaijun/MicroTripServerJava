package com.microtrip.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.common.ApiError;
import com.microtrip.server.revocation.RevocationService;
import com.microtrip.server.security.IpAllowList;
import com.microtrip.server.security.JwtAuthenticationFilter;
import com.microtrip.server.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Spring Security 配置（无状态 JWT + 方法级角色鉴权）。
 *
 * <p>公开接口：/health、/actuator/health、/actuator/info、/auth/register、/auth/login、
 * /scenery/**、/food/**、Swagger/OpenAPI 文档。<br>
 * 受保护接口：/auth/logout、/trajectory/**、/vision/**（缺/无效令牌 → 401）。<br>
 * 管理接口：/admin/**、/api/v1/admin/** 需已认证，且方法级 {@code @PreAuthorize("hasRole('ADMIN')")}
 * 要求 {@code ADMIN} 角色（普通用户 → 403）。<br>
 * 指标端点：/actuator/metrics、/actuator/prometheus 仅允许白名单来源 IP
 * （{@code microtrip.security.actuator-allow-list}，默认仅本机）。</p>
 *
 * <p>版本化：所有业务接口同时暴露 {@code /api/v1/...}（规范路径）与历史裸路径（向后兼容）。</p>
 */
@Configuration
@EnableMethodSecurity   // 启用 @PreAuthorize（角色化封禁）
public class SecurityConfig {

    @Value("${microtrip.cors.allowed-origin:*}")
    private String allowedOrigin;

    /** 生产/https profile 下为 true：强制所有请求经 HTTPS（依赖反向代理转发 X-Forwarded-*） */
    @Value("${microtrip.security.require-https:false}")
    private boolean requireHttps;

    /** 允许访问 Actuator 非公开端点（metrics / prometheus 等）的来源 IP / CIDR */
    @Value("${microtrip.security.actuator-allow-list:127.0.0.1,::1}")
    private String actuatorAllowList;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10); // cost 10，与 Node 版 bcryptjs 一致
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtUtil jwtUtil,
                                           ObjectMapper objectMapper,
                                           RevocationService revocation) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtUtil, objectMapper, revocation);

        // 白名单只解析一次（filterChain 是单例 Bean），避免每个请求都做字符串解析
        IpAllowList actuatorAllowedFrom = IpAllowList.of(actuatorAllowList);

        HttpSecurity sec = http
            // JWT 无状态，关闭 CSRF
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ---------- 指标端点：按来源 IP 收敛（必须排在最前，规则按声明顺序匹配）----------
                // 为什么不整体 permitAll：/actuator/metrics、/actuator/prometheus 会暴露
                // JVM 堆使用、连接池等待数、各接口耗时分布，等于一份系统说明书。
                // 为什么用 IP 白名单而不是 management.server.address：后者要求同时自定义
                // management.server.port 才生效，同端口下写成什么都不做，属于"看起来加固了"。
                .requestMatchers("/actuator", "/actuator/**").access(actuatorAuthorization(actuatorAllowedFrom))
                .requestMatchers(
                        "/health", "/api/v1/health",
                        "/auth/register", "/api/v1/auth/register",
                        "/auth/login", "/api/v1/auth/login",
                        "/scenery/**", "/api/v1/scenery/**",
                        "/food/**", "/api/v1/food/**",
                        "/error",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                // 管理接口：路径层要求已认证，角色（ADMIN）由方法级 @PreAuthorize 把关
                .requestMatchers("/admin/**", "/api/v1/admin/**").authenticated()
                .anyRequest().authenticated())
            // 未携带令牌访问受保护接口 → 401 JSON
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((request, response, authException) ->
                        writeError(response, HttpStatus.UNAUTHORIZED, "ERROR", "未提供访问令牌", objectMapper))
                // 已认证但权限不足 → 403；Actuator 路径例外，统一回 404 以隐藏端点存在性
                .accessDeniedHandler((request, response, ex) -> {
                    if (isActuatorPath(request)) {
                        writeError(response, HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在", objectMapper);
                    } else {
                        writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "无访问权限", objectMapper);
                    }
                }))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        // 生产 HTTPS：强制安全通道（反向代理需转发 X-Forwarded-*，见 application-https.yml）
        if (requireHttps) {
            sec.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        return sec.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(allowedOrigin));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(false);   // allowedOrigin 含 "*" 时必须为 false
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    // ============================================================
    // Actuator 访问控制
    // ============================================================

    /**
     * Actuator 端点的授权判定：健康/信息类端点一律公开，其余仅允许白名单来源 IP。
     *
     * <p>由于匹配模式覆盖整个 {@code /actuator/**}，外部访问任意 Actuator 路径
     * （真实存在或不存在）得到的响应完全一致，攻击者无法据此探测端点是否存在。</p>
     */
    private AuthorizationManager<RequestAuthorizationContext> actuatorAuthorization(IpAllowList allowList) {
        return (authentication, context) -> {
            HttpServletRequest request = context.getRequest();
            if (isPublicActuatorPath(request)) {
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(allowList.contains(request));
        };
    }

    /** 判断是否是 Actuator 路径（剥离 contextPath，避免应用部署在子路径时判定失效） */
    private static boolean isActuatorPath(HttpServletRequest request) {
        return actuatorPath(request).startsWith("/actuator");
    }

    /** 公开的 Actuator 端点：探活与基础信息，不含任何内部指标 */
    private static boolean isPublicActuatorPath(HttpServletRequest request) {
        String path = actuatorPath(request);
        return "/actuator".equals(path)
                || "/actuator/".equals(path)
                || path.startsWith("/actuator/health")
                || "/actuator/info".equals(path);
    }

    private static String actuatorPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        return path == null ? "" : path;
    }

    /** 统一的错误响应体，形状与全局异常处理器一致：{ "error": { code, message } } */
    private static void writeError(HttpServletResponse response, HttpStatus status,
                                   String code, String message, ObjectMapper objectMapper) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = Map.of("error", new ApiError(code, message));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
