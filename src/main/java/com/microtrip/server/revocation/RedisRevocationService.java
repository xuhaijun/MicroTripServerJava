package com.microtrip.server.revocation;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 版吊销服务（激活 {@code redis} profile 时生效）。
 *
 * <p>吊销信息写入 Redis，跨实例共享，TTL 由 Redis 自动过期清理；生产多实例部署推荐。
 * 键前缀：
 * <ul>
 *   <li>{@code revoke:<sha256(token)>} → "1"，TTL = 令牌剩余有效期</li>
 *   <li>{@code ban:<userId>} → "1"，TTL = 封禁时长（长期封禁用约 100 年）</li>
 * </ul>
 *
 * <p>依赖 {@link StringRedisTemplate}（由 Spring Boot Redis 自动配置提供，需配置
 * {@code spring.data.redis.host/port}）。</p>
 */
@Component
@Profile("redis")
public class RedisRevocationService implements RevocationService {

    private static final long PERMANENT_BAN_SECONDS = 3153600000L; // ≈ 100 年

    private final StringRedisTemplate redis;

    public RedisRevocationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void revokeToken(String token, long ttlSeconds) {
        long ttl = ttlSeconds <= 0 ? 1 : ttlSeconds;
        redis.opsForValue().set("revoke:" + TokenHash.sha256(token), "1", ttl, TimeUnit.SECONDS);
    }

    @Override
    public boolean isTokenRevoked(String token) {
        return Boolean.TRUE.equals(redis.hasKey("revoke:" + TokenHash.sha256(token)));
    }

    @Override
    public void banUser(String userId, long ttlSeconds) {
        long ttl = ttlSeconds <= 0 ? PERMANENT_BAN_SECONDS : ttlSeconds;
        redis.opsForValue().set("ban:" + userId, "1", ttl, TimeUnit.SECONDS);
    }

    @Override
    public void unbanUser(String userId) {
        redis.delete("ban:" + userId);
    }

    @Override
    public boolean isUserBanned(String userId) {
        return Boolean.TRUE.equals(redis.hasKey("ban:" + userId));
    }
}
