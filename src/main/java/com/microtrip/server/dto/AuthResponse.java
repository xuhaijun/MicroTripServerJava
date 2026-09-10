package com.microtrip.server.dto;

/**
 * 登录/注册响应：{ token, user }
 */
public record AuthResponse(String token, UserDto user) {
}
