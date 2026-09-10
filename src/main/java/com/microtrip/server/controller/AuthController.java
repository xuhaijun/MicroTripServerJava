package com.microtrip.server.controller;

import com.microtrip.server.common.BizException;
import com.microtrip.server.dto.AuthResponse;
import com.microtrip.server.dto.LoginRequest;
import com.microtrip.server.dto.RegisterRequest;
import com.microtrip.server.security.JwtPrincipal;
import com.microtrip.server.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口：/auth/register、/auth/login、/auth/logout、/auth/account（注销）。
 * 与 Node 版路由、请求/响应结构完全对齐。
 */
@RestController
@RequestMapping({"/auth", "/api/v1/auth"})
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /** POST /auth/register → 201 { token, user } */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        if (req == null) throw BizException.badRequest("缺少请求体");
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(req));
    }

    /** POST /auth/login → 200 { token, user } */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        if (req == null) throw BizException.badRequest("缺少请求体");
        return userService.login(req);
    }

    /** POST /auth/logout（需 Bearer）→ 200 { ok: true }；服务端同时吊销该令牌 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        }
        userService.logout(token);
        return Map.of("ok", true);
    }

    /**
     * DELETE /auth/account（需 Bearer）→ 200 { ok: true }
     *
     * <p>账号注销：删除账号及其云端轨迹，并吊销当前令牌。
     * 满足小米等渠道「必须提供账号注销入口且真正删除数据」的合规要求。</p>
     */
    @DeleteMapping("/account")
    public Map<String, Object> deleteAccount(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        }
        userService.deleteAccount(principal == null ? null : principal.sub(), token);
        return Map.of("ok", true);
    }
}
