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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 腾讯云图像分析（tiia）Provider 适配 —— 真实调用实现。
 *
 * <p>能力：调用腾讯云「图像分析 tiia」的 {@code DetectLabel}（图像标签）接口，
 * 对上传图片返回标签列表 {@code [{name, confidence(0-1), category}]}。</p>
 *
 * <p>鉴权：<b>TC3-HMAC-SHA256</b> 签名（腾讯云 API 3.0 标准），仅依赖 JDK
 * {@code java.net.http.HttpClient} 与 {@code javax.crypto}，无第三方 SDK 依赖。
 * 签名逻辑与 Node 版 {@code src/utils/vision/tencent.js} 完全一致。</p>
 *
 * <p>配置（来自 {@link VisionProperties#getTencent()}）：
 * {@code VISION_SECRET_ID} / {@code VISION_SECRET_KEY} / {@code VISION_REGION}（默认 ap-guangzhou）。</p>
 *
 * <p>未配置密钥时 {@link #isConfigured()} 返回 false，由 {@code VisionService} 直接返回 501；
 * 配置后走真实腾讯云调用，不做任何 mock（保证「真实」）。</p>
 *
 * <p>本类<b>不再</b>用 {@code @Component} 注册为 Bean；由 {@code VisionConfig} 按
 * {@code microtrip.vision.provider=tencent} 条件化注入，避免与阿里云/百度云 Provider 冲突。</p>
 */
public class TencentVisionProvider implements VisionProvider {

    private static final String HOST = "tiia.tencentcloudapi.com";
    private static final String SERVICE = "tiia";
    private static final String ACTION = "DetectLabel";
    private static final String VERSION = "2019-05-29";

    private final VisionProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TencentVisionProvider(VisionProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return props.getTencent().isConfigured();
    }

    /**
     * 调用腾讯云识别图片（base64，不含 data: 前缀），返回标签列表。
     *
     * @param base64 图片 base64 编码（已由 VisionService 去除 data: 前缀）
     * @throws BizException 未配置 / 上游错误 / 解析失败 → 由 VisionService 收敛为 501/502
     */
    @Override
    public List<VisionLabel> recognize(String base64) {
        if (!props.getTencent().isConfigured()) {
            throw BizException.notConfigured(
                    "Vision API 未配置：服务端需设置 VISION_SECRET_ID / VISION_SECRET_KEY 后启用真实识图");
        }
        VisionProperties.Tencent t = props.getTencent();
        try {
            String payload = objectMapper.writeValueAsString(Map.of("ImageBase64", base64));
            String body = callApi(t.getSecretId(), t.getSecretKey(), t.getRegion(), payload);

            JsonNode root = objectMapper.readTree(body);
            JsonNode resp = root.get("Response");
            if (resp == null) {
                throw BizException.upstreamError("Vision 响应格式异常：缺少 Response 节点");
            }
            JsonNode err = resp.get("Error");
            if (err != null) {
                String code = err.path("Code").asText("UNKNOWN");
                String msg = err.path("Message").asText("腾讯云返回错误");
                throw BizException.upstreamError("[" + code + "] " + msg);
            }

            JsonNode labels = resp.get("Labels");
            List<VisionLabel> result = new ArrayList<>();
            if (labels != null && labels.isArray()) {
                for (JsonNode l : labels) {
                    String name = l.path("Name").asText("");
                    // 腾讯云置信度为 0-100 整数，归一化到 0-1（与 Node 版一致）
                    double confidence = l.path("Confidence").asDouble(0) / 100.0;
                    String category = l.path("Category").asText("");
                    result.add(new VisionLabel(name, confidence, category));
                }
            }
            return result;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.upstreamError("调用腾讯云 Vision 失败：" + ex.getMessage());
        }
    }

    // ---------------- TC3-HMAC-SHA256 签名 + HTTPS 调用 ----------------

    /** 发起 HTTPS 请求并返回响应体字符串 */
    private String callApi(String secretId, String secretKey, String region, String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String authorization = buildAuthorization(secretId, secretKey, region, timestamp, payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + HOST + "/"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Host", HOST)
                .header("X-TC-Action", ACTION)
                .header("X-TC-Version", VERSION)
                .header("X-TC-Region", region)
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw BizException.upstreamError("Vision HTTP 状态异常：" + response.statusCode());
        }
        return response.body();
    }

    /** 生成 TC3-HMAC-SHA256 Authorization 头（与 Node 版 buildAuthorization 等价） */
    private String buildAuthorization(String secretId, String secretKey, String region,
                                       long timestamp, String payload) throws Exception {
        String date = Instant.ofEpochSecond(timestamp).toString().substring(0, 10); // YYYY-MM-DD (UTC)
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String hashedPayload = sha256Hex(payload);
        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + HOST + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = String.join("\n",
                "POST", "/", "", canonicalHeaders, signedHeaders, hashedPayload);
        String stringToSign = String.join("\n",
                "TC3-HMAC-SHA256", String.valueOf(timestamp), credentialScope, sha256Hex(canonicalRequest));

        // 派生签名密钥：TC3 + secretKey -> date -> SERVICE -> tc3_request
        byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, SERVICE);
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = hex(hmacSha256(secretSigning, stringToSign));

        return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** SHA256 十六进制 */
    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return hex(digest);
    }

    /** HMAC-SHA256（key 为字节数组，data 为 UTF-8 字符串） */
    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /** 字节数组转十六进制 */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
