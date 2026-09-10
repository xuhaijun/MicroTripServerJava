package com.microtrip.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图像识别配置（按厂商组织）。
 *
 * <p>绑定前缀 {@code microtrip.vision}，由 Spring Boot {@code @ConfigurationProperties} 注入：
 * <pre>
 * microtrip.vision.provider=tencent|aliyun|baidu
 * microtrip.vision.tencent.secret-id / secret-key / region
 * microtrip.vision.aliyun.access-key-id / access-key-secret / region
 * microtrip.vision.baidu.api-key / secret-key
 * </pre>
 * 缺失对应厂商密钥时，该厂商 Provider 的 {@code isConfigured()} 返回 false，
 * 由 {@code VisionService} 收敛为 501（不伪造结果，与 Node 版一致）。
 */
@Component
@ConfigurationProperties(prefix = "microtrip.vision")
public class VisionProperties {

    /** 当前生效厂商：tencent（默认）| aliyun | baidu */
    private String provider = "tencent";

    private Tencent tencent = new Tencent();
    private Aliyun aliyun = new Aliyun();
    private Baidu baidu = new Baidu();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Tencent getTencent() {
        return tencent;
    }

    public void setTencent(Tencent tencent) {
        this.tencent = tencent;
    }

    public Aliyun getAliyun() {
        return aliyun;
    }

    public void setAliyun(Aliyun aliyun) {
        this.aliyun = aliyun;
    }

    public Baidu getBaidu() {
        return baidu;
    }

    public void setBaidu(Baidu baidu) {
        this.baidu = baidu;
    }

    /** 腾讯云 tiia */
    public static class Tencent {
        private String secretId = "";
        private String secretKey = "";
        private String region = "ap-guangzhou";

        public boolean isConfigured() {
            return secretId != null && !secretId.isBlank()
                    && secretKey != null && !secretKey.isBlank();
        }

        public String getSecretId() {
            return secretId;
        }

        public void setSecretId(String secretId) {
            this.secretId = secretId;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    /** 阿里云图像识别（imagerecog） */
    public static class Aliyun {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String region = "cn-shanghai";

        public boolean isConfigured() {
            return accessKeyId != null && !accessKeyId.isBlank()
                    && accessKeySecret != null && !accessKeySecret.isBlank();
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    /** 百度图像识别（AIP） */
    public static class Baidu {
        private String apiKey = "";
        private String secretKey = "";

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank()
                    && secretKey != null && !secretKey.isBlank();
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }
}
