package com.microtrip.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量限流过滤器（登录/注册等高频公开接口防爆破）。
 *
 * <p>固定窗口计数器：以「客户端 IP + 路径」为键，在 {@code window-seconds} 窗口内超过
 * {@code capacity} 次即返回 429。内存实现，单实例可用；多实例生产建议换 Redis 令牌桶
 * （Bucket4j + Redis）。默认仅作用于 {@code /auth/login}、{@code /auth/register} 及其 v1 路径。</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher matcher = new AntPathMatcher();

    /** 窗口状态：count + 窗口起点（epoch ms） */
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled()) return true;
        String path = request.getServletPath();
        return props.getPaths().stream().noneMatch(p -> matcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String key = clientIp(request) + "|" + request.getServletPath();
        long now = System.currentTimeMillis();
        long windowMs = props.getWindowSeconds() * 1000L;

        long[] bucket = buckets.compute(key, (k, old) -> {
            if (old == null || now - old[1] >= windowMs) {
                return new long[]{1L, now};           // 新窗口
            }
            old[0] = old[0] + 1;
            return old;
        });

        if (bucket[0] > props.getCapacity()) {
            long resetIn = Math.max(0, (bucket[1] + windowMs - now) / 1000L);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(resetIn));
            Map<String, Object> body = Map.of(
                    "error", Map.of("code", "RATE_LIMITED",
                            "message", "请求过于频繁，请 " + resetIn + " 秒后再试"));
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
