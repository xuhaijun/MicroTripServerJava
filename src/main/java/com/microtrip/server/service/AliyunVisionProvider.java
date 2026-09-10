package com.microtrip.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microtrip.server.common.BizException;
import com.microtrip.server.config.VisionProperties;
import com.microtrip.server.dto.VisionLabel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云图像识别（imagerecog · {@code TaggingImage} 通用图像标签）Provider。
 *
 * <p>能力：调用阿里云「图像识别」的 {@code TaggingImage} 接口，对图片返回标签列表
 * {@code [{name, confidence(0-1), category}]}。</p>
 *
 * <p>鉴权：阿里云 RPC 签名（POP 签名 V1，HMAC-SHA1），仅依赖 JDK
 * {@code java.net.http.HttpClient} 与 {@code javax.crypto}，无 SDK 依赖。</p>
 *
 * <p>配置（来自 {@link VisionProperties#getAliyun()}）：
 * {@code ALIYUN_AK} / {@code ALIYUN_SK} / {@code ALIYUN_REGION}（默认 cn-shanghai）。</p>
 *
 * <p>未配置密钥时 {@link #isConfigured()} 返回 false，由 {@code VisionService} 返回 501；
 * 配置后走真实阿里云调用，不 mock。</p>
 *
 * <p>注意：本实现按阿里云 RPC 通用签名规范编写，字段名（如 base64 字段 {@code ImageContent}）
 * 以阿里云官方文档为准；部署前需用真实 AK/SK 联调校准（沙箱无密钥/外网，未实测）。</p>
 */
public class AliyunVisionProvider implements VisionProvider {

    private static final String PRODUCT = "imagerecog";
    private static final String ACTION = "TaggingImage";
    private static final String VERSION = "2019-09-30";

    private final VisionProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public AliyunVisionProvider(VisionProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public boolean isConfigured() {
        return props.getAliyun().isConfigured();
    }

    @Override
    public List<VisionLabel> recognize(String base64) {
        if (!props.getAliyun().isConfigured()) {
            throw BizException.notConfigured(
                    "Vision API 未配置：服务端需设置 ALIYUN_AK / ALIYUN_SK 后启用阿里云识图");
        }
        VisionProperties.Aliyun a = props.getAliyun();
        try {
            String host = PRODUCT + "." + a.getRegion() + ".aliyuncs.com";
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC).format(java.time.Instant.now());
            String nonce = UUID.randomUUID().toString();

            TreeMap<String, String> params = new TreeMap<>();
            params.put("Action", ACTION);
            params.put("Version", VERSION);
            params.put("RegionId", a.getRegion());
            params.put("AccessKeyId", a.getAccessKeyId());
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", nonce);
            params.put("Timestamp", timestamp);
            params.put("Format", "JSON");
            // base64 图片内容字段（以阿里云官方文档为准）
            params.put("ImageContent", base64);

            String signature = sign(a.getAccessKeySecret(), params);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
            }
            body.append('&').append("Signature=").append(percentEncode(signature));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + host + "/"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw BizException.upstreamError("Aliyun Vision HTTP 状态异常：" + resp.statusCode());
            }
            return parse(resp.body());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.upstreamError("调用阿里云 Vision 失败：" + ex.getMessage());
        }
    }

    private List<VisionLabel> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        // 阿里云错误响应形态：{ "Code": "Xxx", "Message": "..." }（成功时 Code=Success 或不含 Code）
        JsonNode code = root.get("Code");
        if (code != null && !code.asText().equalsIgnoreCase("Success") && !code.asText().isEmpty()) {
            String msg = root.path("Message").asText("阿里云返回错误");
            throw BizException.upstreamError("[" + code.asText() + "] " + msg);
        }
        JsonNode tags = root.get("Tags");
        List<VisionLabel> result = new ArrayList<>();
        if (tags != null && tags.isArray()) {
            for (JsonNode t : tags) {
                String name = t.path("Tag").asText("");
                double confidence = t.path("Confidence").asDouble(0); // 阿里云该接口已为 0-1
                result.add(new VisionLabel(name, confidence, null));
            }
        }
        return result;
    }

    /** 阿里云 POP 签名 V1：HMAC-SHA1(accessKeySecret + "&", StringToSign) → Base64 */
    private String sign(String secret, TreeMap<String, String> params) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (canonical.length() > 0) canonical.append('&');
            canonical.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
        }
        String stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonical.toString());

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(raw);
    }

    /** 阿里云专用 percent-encode：除 A-Za-z0-9-_.~ 外全部编码，空格→%20 */
    private String percentEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else         if (c == ' ') {
                sb.append("%20");
            } else {
                sb.append(String.format("%%%02X", c));
            }
        }
        return sb.toString();
    }
}
