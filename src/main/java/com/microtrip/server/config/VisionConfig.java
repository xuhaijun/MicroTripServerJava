package com.microtrip.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.service.AliyunVisionProvider;
import com.microtrip.server.service.BaiduVisionProvider;
import com.microtrip.server.service.TencentVisionProvider;
import com.microtrip.server.service.VisionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图像识别 Provider 选择：按 {@code microtrip.vision.provider} 注入<b>唯一</b> Provider Bean。
 *
 * <ul>
 *   <li>{@code tencent}（默认，缺省即生效）→ {@link TencentVisionProvider}</li>
 *   <li>{@code aliyun} → {@link AliyunVisionProvider}</li>
 *   <li>{@code baidu} → {@link BaiduVisionProvider}</li>
 * </ul>
 *
 * <p>三个条件互斥，保证容器中仅一个 {@link VisionProvider}，{@code VisionService} 注入无歧义。
 * 各 Provider 自身负责「未配置密钥 → isConfigured()=false → 501」。</p>
 */
@Configuration
public class VisionConfig {

    @Bean
    @ConditionalOnProperty(name = "microtrip.vision.provider", havingValue = "tencent", matchIfMissing = true)
    public VisionProvider tencentProvider(VisionProperties props, ObjectMapper objectMapper) {
        return new TencentVisionProvider(props, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "microtrip.vision.provider", havingValue = "aliyun")
    public VisionProvider aliyunProvider(VisionProperties props, ObjectMapper objectMapper) {
        return new AliyunVisionProvider(props, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "microtrip.vision.provider", havingValue = "baidu")
    public VisionProvider baiduProvider(VisionProperties props, ObjectMapper objectMapper) {
        return new BaiduVisionProvider(props, objectMapper);
    }
}
