package com.microtrip.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.microtrip.server.revocation.RevocationService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * JWT 认证过滤器：在 {@code UsernamePasswordAuthenticationFilter} 之前执行。
 *
 * <ul>
 *   <li>无 {@code Authorization} 头 → 不设置认证（公开接口放行，受保护接口由
 *       Security 的 {@code AuthenticationEntryPoint} 返回 401）。</li>
 *   <li>有 {@code Bearer} 且有效 → 设置 {@code SecurityContext}，后续控制器通过
 *       {@code @AuthenticationPrincipal JwtPrincipal} 取用户。</li>
 *   <li>有 {@code Bearer} 但无效/过期/<b>已吊销</b> → 写出 401 JSON 并短路。</li>
 *   <li>令牌有效但<b>所属用户被封禁</b> → 写出 403 JSON 并短路。</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final RevocationService revocation;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, ObjectMapper objectMapper, RevocationService revocation) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.revocation = revocation;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                JwtPrincipal principal = jwtUtil.toPrincipal(jwtUtil.verify(token));
                // 主动登出：token 已进入黑名单 → 视为无效
                if (revocation.isTokenRevoked(token)) {
                    writeUnauthorized(response, "令牌已失效（已登出）");
                    return;
                }
                // 主动封禁：用户被禁 → 拒绝访问
                if (revocation.isUserBanned(principal.sub())) {
                    writeForbidden(response);
                    return;
                }
                // 角色 → Spring Security 权限（ROLE_USER / ROLE_ADMIN），
                // 供 @PreAuthorize("hasRole('ADMIN')") 等角色化拦截使用
                String role = (principal.role() == null || principal.role().isBlank())
                        ? "USER" : principal.role();
                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + role));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ex) {
                writeUnauthorized(response, "令牌无效或已过期");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = Map.of(
                "error", Map.of("code", "ERROR", "message", message));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = Map.of(
                "error", Map.of("code", "FORBIDDEN", "message", "账号已被封禁"));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
