package com.microtrip.server.controller;

import com.microtrip.server.dto.BanRequest;
import com.microtrip.server.revocation.RevocationService;
import com.microtrip.server.security.JwtPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理接口（封禁 / 解封）。
 *
 * <p><b>鉴权（演进：角色化 OAuth2 风格）</b>：不再使用轻量 {@code X-Admin-Token} 闸门，
 * 改为<b>基于 JWT 角色</b>的 Spring Security 方法级拦截：
 * <ul>
 *   <li>接口路径 {@code /admin/**}、{@code /api/v1/admin/**} 在过滤链层要求已认证；</li>
 *   <li>方法上的 {@code @PreAuthorize("hasRole('ADMIN')")} 进一步要求 {@code ADMIN} 角色；
 *       普通用户（{@code ROLE_USER}）调用返回 403。</li>
 * </ul>
 * 角色来自 JWT {@code role} 声明，由 {@code AdminBootstrapService} 在启动时按配置注入管理员账号。</p>
 *
 * <p>封禁状态由 {@link RevocationService#banUser(String, long)} 写入，过滤链对所有
 * 被封禁用户的令牌返回 403，实现「主动封禁」。</p>
 */
@RestController
@RequestMapping({"/admin", "/api/v1/admin"})
public class AdminController {

    private final RevocationService revocation;

    public AdminController(RevocationService revocation) {
        this.revocation = revocation;
    }

    /** POST /admin/ban → 200 { ok: true }；封禁指定用户（需 ADMIN 角色） */
    @PostMapping("/ban")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> ban(@Valid @RequestBody BanRequest req,
                                                    @AuthenticationPrincipal JwtPrincipal admin) {
        if (req.userId() == null || req.userId().isBlank()) {
            throw com.microtrip.server.common.BizException.badRequest("userId 不能为空");
        }
        long ttl = req.ttlSeconds() == null ? 0 : req.ttlSeconds();
        revocation.banUser(req.userId(), ttl);
        return ResponseEntity.ok(Map.of("ok", true, "by", admin == null ? null : admin.sub()));
    }

    /** POST /admin/unban → 200 { ok: true }；解除封禁（需 ADMIN 角色） */
    @PostMapping("/unban")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> unban(@Valid @RequestBody BanRequest req,
                                                      @AuthenticationPrincipal JwtPrincipal admin) {
        if (req.userId() == null || req.userId().isBlank()) {
            throw com.microtrip.server.common.BizException.badRequest("userId 不能为空");
        }
        revocation.unbanUser(req.userId());
        return ResponseEntity.ok(Map.of("ok", true, "by", admin == null ? null : admin.sub()));
    }
}
