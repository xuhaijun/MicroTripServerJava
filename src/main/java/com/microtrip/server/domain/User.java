package com.microtrip.server.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户账号实体（对应原 Node 版 users 表）。
 * 密码以 BCrypt 哈希存储，绝不明文。
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_phone", columnNames = "phone")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /** 用户 ID（服务端随机串，避免自增 ID 泄露用户量），如 u_xxx */
    @Id
    @Column(length = 64)
    private String id;

    /** 11 位手机号（登录标识，唯一） */
    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    /** BCrypt 哈希（cost 10） */
    @Column(nullable = false, length = 80)
    private String password;

    /** 昵称 */
    @Column(nullable = false, length = 64)
    private String nickname;

    /** 头像 URL（可空） */
    @Column(length = 512)
    private String avatar;

    /**
     * 角色：USER（默认） | ADMIN。
     * 写入 JWT {@code role} 声明，过滤链据此注入 {@code ROLE_*} 权限，
     * 管理接口 {@code @PreAuthorize("hasRole('ADMIN')")} 依赖此声明。
     * 默认值 USER，兼容历史数据（ddl-auto: update 自动补列）。
     */
    @Column(name = "role", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) DEFAULT 'USER'")
    private String role;

    /** 注册时间（毫秒时间戳） */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
