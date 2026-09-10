package com.microtrip.server.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * JWT 签名密钥解析与持久化。
 *
 * <p>解析优先级（与 Node 版 {@code config.js} 完全一致）：
 * <ol>
 *   <li>环境变量 {@code JWT_SECRET}（最高优先，便于容器/编排注入）</li>
 *   <li>文件 {@code .jwt_secret}（首次随机生成后写入，重启仍可校验旧 token）</li>
 *   <li>以上皆无 → 启动时刻随机生成 48 字节 hex 并持久化到文件</li>
 * </ol>
 * 密钥长度 ≥ 256 bit（HS256 最低要求），随机串为 96 个 hex 字符（48 字节）。
 * </p>
 */
@Component
public class JwtSecretService {

    @Value("${microtrip.jwt.secret:}")
    private String configuredSecret;

    @Value("${microtrip.jwt.expires-in-seconds:2592000}")
    private long expiresInSeconds;

    /**
     * 生产强制外部化：为 true 时，若未通过环境变量 {@code JWT_SECRET} 注入密钥，
     * 启动即失败（fail-fast），避免把随机生成/文件密钥用于生产（密钥托管要求）。
     * 配合 {@code prod} profile 开启，密钥由 KMS / 配置中心注入。
     */
    @Value("${microtrip.jwt.require-externalized:false}")
    private boolean requireExternalized;

    private String resolvedSecret;

    @PostConstruct
    public void init() {
        if (configuredSecret != null && configuredSecret.trim().length() >= 32) {
            this.resolvedSecret = configuredSecret.trim();
            return;
        }
        // 生产要求外部化但缺失 → 直接失败，强制从 KMS/配置中心注入
        if (requireExternalized) {
            throw new IllegalStateException(
                    "JWT 密钥未外部化：生产环境必须通过环境变量 JWT_SECRET 注入（KMS/配置中心），"
                            + "已开启 microtrip.jwt.require-externalized=true 但 microtrip.jwt.secret 为空。");
        }
        Path file = Path.of(".jwt_secret");
        try {
            if (Files.exists(file)) {
                String existing = Files.readString(file).trim();
                if (existing.length() >= 32) {
                    this.resolvedSecret = existing;
                    return;
                }
            }
            // 生成并持久化
            byte[] raw = new byte[48];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            this.resolvedSecret = sb.toString();
            Files.writeString(file, this.resolvedSecret);
        } catch (IOException e) {
            // 文件不可写时退化为内存随机密钥（重启后旧 token 失效，仅影响开发态）
            byte[] raw = new byte[48];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            this.resolvedSecret = sb.toString();
        }
    }

    public String getSecret() {
        return resolvedSecret;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
