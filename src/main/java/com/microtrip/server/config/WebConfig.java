package com.microtrip.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 装配：注册静态缓存拦截器 + 限流过滤器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StaticCacheInterceptor staticCacheInterceptor;
    private final RateLimitProperties rateLimitProperties;
    private final ObjectMapper objectMapper;

    public WebConfig(StaticCacheInterceptor staticCacheInterceptor,
                     RateLimitProperties rateLimitProperties,
                     ObjectMapper objectMapper) {
        this.staticCacheInterceptor = staticCacheInterceptor;
        this.rateLimitProperties = rateLimitProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(staticCacheInterceptor);
    }

    /**
     * 限流过滤器：注册为 servlet 过滤器且置于最高优先级（先于 Spring Security 链），
     * 对登录/注册等高频公开接口做防爆破拦截。
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimitProperties, objectMapper));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setName("rateLimitFilter");
        return bean;
    }
}
