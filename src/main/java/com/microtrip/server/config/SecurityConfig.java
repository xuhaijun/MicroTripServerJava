package com.microtrip.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.common.ApiError;
import com.microtrip.server.revocation.RevocationService;
import com.microtrip.server.security.JwtAuthenticationFilter;
import com.microtrip.server.security.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
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
 * <p>公开接口：/health、/actuator/health、/auth/register、/auth/login、
 * /scenery/**、/food/**、Swagger/OpenAPI 文档。<br>
 * 受保护接口：/auth/logout、/trajectory/**、/vision/**（缺/无效令牌 → 401）。<br>
 * 管理接口：/admin/**、/api/v1/admin/** 需已认证，且方法级 {@code @PreAuthorize("hasRole('ADMIN')")}
 * 要求 {@code ADMIN} 角色（普通用户 → 403）。</p>
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

        HttpSecurity sec = http
            // JWT 无状态，关闭 CSRF
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/health", "/api/v1/health",
                        "/actuator", "/actuator/**",
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
            .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                Map<String, Object> body = Map.of(
                        "error", new ApiError("ERROR", "未提供访问令牌"));
                response.getWriter().write(objectMapper.writeValueAsString(body));
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
}
