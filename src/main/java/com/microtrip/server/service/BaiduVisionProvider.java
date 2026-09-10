package com.microtrip.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.common.BizException;
import com.microtrip.server.config.VisionProperties;
import com.microtrip.server.dto.VisionLabel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度图像识别（AIP · {@code advanced_general} 通用物体和场景识别）Provider。
 *
 * <p>能力：调用百度「图像识别」的 {@code advanced_general} 接口，对图片返回标签列表
 * {@code [{name, confidence(0-1), category}]}。</p>
 *
 * <p>鉴权：先以 API Key / Secret Key 换取 {@code access_token}（OAuth2 client_credentials），
 * 再携 token 调用识别接口；token 内存缓存至过期前刷新。仅依赖 JDK HTTP 客户端，无 SDK 依赖。</p>
 *
 * <p>配置（来自 {@link VisionProperties#getBaidu()}）：
 * {@code BAIDU_API_KEY} / {@code BAIDU_SECRET_KEY}。</p>
 *
 * <p>未配置密钥时 {@link #isConfigured()} 返回 false，由 {@code VisionService} 返回 501；
 * 配置后走真实百度调用，不 mock。</p>
 *
 * <p>注意：沙箱无密钥/外网，真实链路未实测；字段与端点以百度智能云官方文档为准。</p>
 */
public class BaiduVisionProvider implements VisionProvider {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String ADVANCED_URL = "https://aip.baidubce.com/rest/2.0/image-classify/v2/advanced_general";

    private final VisionProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // access_token 缓存（内存），过期前复用
    private volatile String cachedToken;
    private volatile long tokenExpireAt = 0; // epoch ms

    public BaiduVisionProvider(VisionProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public boolean isConfigured() {
        return props.getBaidu().isConfigured();
    }

    @Override
    public List<VisionLabel> recognize(String base64) {
        if (!props.getBaidu().isConfigured()) {
            throw BizException.notConfigured(
                    "Vision API 未配置：服务端需设置 BAIDU_API_KEY / BAIDU_SECRET_KEY 后启用百度识图");
        }
        try {
            String token = getAccessToken();
            String body = "image=" + encode(base64);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ADVANCED_URL + "?access_token=" + token))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw BizException.upstreamError("Baidu Vision HTTP 状态异常：" + resp.statusCode());
            }
            return parse(resp.body());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.upstreamError("调用百度 Vision 失败：" + ex.getMessage());
        }
    }

    /** 获取 access_token（带内存缓存，提前 5 分钟刷新） */
    private String getAccessToken() throws Exception {
        VisionProperties.Baidu b = props.getBaidu();
        if (cachedToken != null && Instant.now().toEpochMilli() < tokenExpireAt - 300_000) {
            return cachedToken;
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + b.getApiKey()
                + "&client_secret=" + b.getSecretKey();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.has("error")) {
            throw BizException.upstreamError("获取百度 token 失败：" + root.path("error_description").asText(root.path("error").asText()));
        }
        String token = root.path("access_token").asText("");
        long expiresIn = root.path("expires_in").asLong(0); // 秒
        if (token.isEmpty()) {
            throw BizException.upstreamError("获取百度 token 失败：响应缺少 access_token");
        }
        cachedToken = token;
        tokenExpireAt = Instant.now().toEpochMilli() + expiresIn * 1000L;
        return token;
    }

    private List<VisionLabel> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.has("error_code")) {
            throw BizException.upstreamError("[" + root.path("error_code").asText()
                    + "] " + root.path("error_msg").asText("百度返回错误"));
        }
        JsonNode result = root.get("result");
        List<VisionLabel> labels = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode r : result) {
                String name = r.path("keyword").asText("");
                double confidence = r.path("score").asDouble(0); // 百度 score 为 0-1
                labels.add(new VisionLabel(name, confidence, null));
            }
        }
        return labels;
    }

    private String encode(String base64) {
        // 表单 URL 编码：base64 仅含 A-Za-z0-9+/=，均无需编码，直接返回
        return base64;
    }
}
