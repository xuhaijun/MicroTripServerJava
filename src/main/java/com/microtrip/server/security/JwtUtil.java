package com.microtrip.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发 / 校验工具（jjwt 0.12.x，算法 HS256）。
 *
 * <p>Token payload 与 App 端 AuthService 严格对齐（附加 {@code role} 声明，向后兼容）：
 * <pre>{ sub: userId, name: nickname, phone, role, iat, exp }</pre>
 * 有效期取自 {@link JwtSecretService#getExpiresInSeconds()}（默认 30 天）。</p>
 */
@Component
public class JwtUtil {

    private final JwtSecretService secretService;
    private SecretKey key;

    public JwtUtil(JwtSecretService secretService) {
        this.secretService = secretService;
    }

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secretService.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 为用户签发 JWT（返回紧凑字符串）；role 决定过滤链注入的 ROLE_* 权限 */
    public String sign(String userId, String name, String phone, String role) {
        long now = System.currentTimeMillis();
        long exp = now + secretService.getExpiresInSeconds() * 1000L;
        return Jwts.builder()
                .subject(userId)
                .claim("name", name)
                .claim("phone", phone)
                .claim("role", role == null ? "USER" : role)
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验 token，成功返回 Claims；失败（签名/过期）抛 JwtException */
    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 返回 token 的剩余有效期（秒，最小为 0）；用于登出时设置黑名单 TTL */
    public long ttlSeconds(String token) {
        Claims claims = verify(token);
        long exp = claims.getExpiration().getTime();
        return Math.max(0, (exp - System.currentTimeMillis()) / 1000L);
    }

    /** 从 Claims 构造认证主体 */
    public JwtPrincipal toPrincipal(Claims claims) {
        return new JwtPrincipal(
                claims.getSubject(),
                claims.get("name", String.class),
                claims.get("phone", String.class),
                claims.get("role", String.class));
    }
}
