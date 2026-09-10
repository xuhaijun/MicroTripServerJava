package com.microtrip.server.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 静态只读接口缓存：/food、/scenery 为「城市维度静态数据」，
 * GET 命中后浏览器/CDN/代理可缓存 5 分钟，大幅削减重复请求。
 * （trajectory 为按用户动态数据、auth/vision 更不应缓存，故不在此列。）
 *
 * <p>对应 Node 版 app.js 中 {@code cacheStaticReadOnly} 中间件。</p>
 */
@Component
public class StaticCacheInterceptor implements HandlerInterceptor {

    private static final String CACHE_CONTROL = "public, max-age=300";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (request.getRequestURI().startsWith("/food")
                    || request.getRequestURI().startsWith("/scenery"))) {
            response.setHeader("Cache-Control", CACHE_CONTROL);
        }
        return true;
    }
}
