package com.microtrip.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体：{ phone, password }
 */
public record LoginRequest(
        @NotBlank(message = "请输入手机号") String phone,
        @NotBlank(message = "请输入密码") String password) {
}
