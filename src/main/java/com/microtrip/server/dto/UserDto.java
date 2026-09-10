package com.microtrip.server.dto;

/**
 * 用户对外视图（绝不返回 password）。
 * 字段与 App 端 AuthService 解析保持一致。
 */
public record UserDto(
        String id,
        String nickname,
        String avatar,
        String phone,
        String loginType,
        Long createdAt) {

    public static UserDto from(com.microtrip.server.domain.User u) {
        return new UserDto(
                u.getId(),
                u.getNickname(),
                u.getAvatar() == null ? "" : u.getAvatar(),
                u.getPhone(),
                "server",
                u.getCreatedAt());
    }
}
