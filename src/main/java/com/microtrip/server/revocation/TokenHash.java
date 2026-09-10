package com.microtrip.server.revocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 令牌哈希工具：把原始 JWT 串映射为 SHA-256 十六进制摘要。
 *
 * <p>黑名单只存摘要，不落明文 token，降低泄露风险；同一 token 摘要稳定，
 * 便于「吊销 / 命中查询」对称计算。</p>
 */
public final class TokenHash {

    private TokenHash() {
    }

    public static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
