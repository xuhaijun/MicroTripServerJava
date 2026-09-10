package com.microtrip.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查（部署探活用）：GET /health → { ok, service, time }。
 * 与 Node 版接口路径、结构一致。另有 Spring Actuator /actuator/health 供编排系统使用。
 */
@RestController
public class HealthController {

    @GetMapping({"/health", "/api/v1/health"})
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "service", "micro-trip-server",
                "time", System.currentTimeMillis());
    }
}
