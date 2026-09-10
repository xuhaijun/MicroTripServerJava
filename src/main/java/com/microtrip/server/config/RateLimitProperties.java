package com.microtrip.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 限流配置（前缀 {@code microtrip.ratelimit}）。
 */
@Component
@ConfigurationProperties(prefix = "microtrip.ratelimit")
public class RateLimitProperties {

    /** 总开关（默认开；测试可关） */
    private boolean enabled = true;

    /** 窗口内允许的最大请求数 */
    private int capacity = 60;

    /** 窗口时长（秒） */
    private int windowSeconds = 60;

    /** 受限路径（Ant 风格） */
    private List<String> paths = List.of(
            "/auth/login", "/auth/register",
            "/api/v1/auth/login", "/api/v1/auth/register");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> paths) { this.paths = paths; }
}
