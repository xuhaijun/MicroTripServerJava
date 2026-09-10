package com.microtrip.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体：{ phone, password, nickname }
 */
public record RegisterRequest(
        @NotBlank(message = "请输入手机号") String phone,
        @NotBlank(message = "请输入密码") @Size(min = 6, message = "密码至少 6 位") String password,
        @NotBlank(message = "请输入昵称") String nickname) {
}
