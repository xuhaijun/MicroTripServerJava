package com.microtrip.server.service;

import com.microtrip.server.dto.VisionLabel;

import java.util.List;

/**
 * 图像识别 Provider 抽象。默认实现 {@link TencentVisionProvider}：未配置密钥时
 * {@link #isConfigured()} 返回 false，由 VisionService 直接返回 501；
 * 配置后实现真实「腾讯云图像分析 tiia · DetectLabel」调用（见 TencentVisionProvider）。
 */
public interface VisionProvider {

    /** 是否已配置第三方密钥 */
    boolean isConfigured();

    /**
     * 调用第三方识别，返回标签列表。
     * 未配置时不应被调用（由 VisionService 拦截为 501）。
     */
    List<VisionLabel> recognize(String base64) throws Exception;
}
