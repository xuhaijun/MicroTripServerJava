package com.microtrip.server.security;

/**
 * JWT 校验通过后放入 {@code SecurityContext} 的认证主体。
 * 对应 Node 版 {@code req.user}（{@code sub} 即用户 ID）。
 *
 * <p>{@code role} 用于在 Spring Security 中注入 {@code ROLE_*} 权限，
 * 供管理接口 {@code @PreAuthorize("hasRole('ADMIN')")} 做角色化拦截。</p>
 */
public record JwtPrincipal(String sub, String name, String phone, String role) {

    /** 是否管理员（ADMIN 角色） */
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
