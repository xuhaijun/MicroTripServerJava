package com.microtrip.server.revocation;

/**
 * 令牌吊销 / 用户封禁 抽象。
 *
 * <p>解决 JWT 无状态带来的两个局限：
 * <ul>
 *   <li><b>主动登出</b>：客户端丢弃 token 的同时，服务端把该 token 加入黑名单，
 *       过滤链据此拒绝（即使 token 未过期）。</li>
 *   <li><b>主动封禁</b>：把用户 ID 加入封禁集合，过滤链对所有该用户令牌返回 403。</li>
 * </ul>
 *
 * <p>吊销以「原始 token 的 SHA-256」为键，不改 JWT payload，对外契约（Flutter 端）零变化。
 * 键上带 TTL，到期自动失效，避免集合无限增长。</p>
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link InMemoryRevocationService}：默认（非 redis profile），单实例内存表，开发/冒烟足够。</li>
 *   <li>{@link RedisRevocationService}：激活 {@code redis} profile 时启用，跨实例共享，生产推荐。</li>
 * </ul>
 */
public interface RevocationService {

    /** 吊销指定 token（TTL 秒，通常传「剩余有效期」），到期自动清除 */
    void revokeToken(String token, long ttlSeconds);

    /** 该 token 是否已被吊销 */
    boolean isTokenRevoked(String token);

    /** 封禁用户（TTL 秒，<=0 表示长期封禁） */
    void banUser(String userId, long ttlSeconds);

    /** 解除封禁 */
    void unbanUser(String userId);

    /** 该用户是否处于封禁状态 */
    boolean isUserBanned(String userId);
}
