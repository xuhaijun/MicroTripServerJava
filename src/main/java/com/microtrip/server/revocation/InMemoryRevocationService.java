package com.microtrip.server.revocation;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版吊销服务（默认实现，非 redis profile 生效）。
 *
 * <p>适用于单实例部署与开发/冒烟测试；吊销信息不跨实例共享。
 * 过期时间采用「绝对过期时间戳」，查询时惰性清理，避免定时任务。</p>
 */
@Component
@Profile("!redis")
public class InMemoryRevocationService implements RevocationService {

    /** token 摘要 → 绝对过期时间（epoch ms） */
    private final Map<String, Long> revokedTokens = new ConcurrentHashMap<>();
    /** userId → 绝对过期时间（epoch ms） */
    private final Map<String, Long> bannedUsers = new ConcurrentHashMap<>();

    @Override
    public void revokeToken(String token, long ttlSeconds) {
        long ttl = ttlSeconds <= 0 ? 1 : ttlSeconds;
        revokedTokens.put(TokenHash.sha256(token), System.currentTimeMillis() + ttl * 1000L);
    }

    @Override
    public boolean isTokenRevoked(String token) {
        String key = TokenHash.sha256(token);
        Long expireAt = revokedTokens.get(key);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= System.currentTimeMillis()) {
            revokedTokens.remove(key);
            return false;
        }
        return true;
    }

    @Override
    public void banUser(String userId, long ttlSeconds) {
        long expireAt = ttlSeconds <= 0
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + ttlSeconds * 1000L;
        bannedUsers.put(userId, expireAt);
    }

    @Override
    public void unbanUser(String userId) {
        bannedUsers.remove(userId);
    }

    @Override
    public boolean isUserBanned(String userId) {
        Long expireAt = bannedUsers.get(userId);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= System.currentTimeMillis()) {
            bannedUsers.remove(userId);
            return false;
        }
        return true;
    }
}
