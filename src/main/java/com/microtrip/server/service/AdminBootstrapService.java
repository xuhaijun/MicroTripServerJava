package com.microtrip.server.service;

import com.microtrip.server.domain.User;
import com.microtrip.server.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 启动时注入管理员账号（角色化封禁的信任根）。
 *
 * <p>配置（环境变量优先）后，若账号不存在则创建、已存在则确保角色为 {@code ADMIN}：
 * <pre>
 *   microtrip.admin.bootstrap.phone=13900000000
 *   microtrip.admin.bootstrap.password=****      # 建议来自 KMS / 配置中心，勿硬编码
 *   microtrip.admin.bootstrap.nickname=超级管理员
 * </pre>
 * 未配置时（生产推荐）<b>不自动创建</b>管理员 —— 应通过 IdP / OAuth2 把 ADMIN 角色授予真实员工，
 * 本服务只认 JWT 里的 {@code role=ADMIN} 声明。</p>
 */
@Component
public class AdminBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final UserRepository userRepository;
    private final PasswordEncoder encoder;

    @Value("${microtrip.admin.bootstrap.phone:}")
    private String phone;

    @Value("${microtrip.admin.bootstrap.password:}")
    private String password;

    @Value("${microtrip.admin.bootstrap.nickname:超级管理员}")
    private String nickname;

    public AdminBootstrapService(UserRepository userRepository, PasswordEncoder encoder) {
        this.userRepository = userRepository;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            log.info("[admin-bootstrap] 未配置 bootstrap 账号，跳过自动创建（生产建议由 IdP/OAuth2 授予 ADMIN 角色）");
            return;
        }
        if (!PHONE.matcher(phone).matches()) {
            log.warn("[admin-bootstrap] 配置的 phone={} 非法（需 11 位手机号），跳过", phone);
            return;
        }
        userRepository.findByPhone(phone).ifPresentOrElse(user -> {
            if (!"ADMIN".equals(user.getRole())) {
                user.setRole("ADMIN");
                userRepository.save(user);
                log.info("[admin-bootstrap] 已为已有账号 {} 赋权 ADMIN", phone);
            }
        }, () -> {
            String id = "u_admin_" + Math.abs(phone.hashCode());
            User admin = User.builder()
                    .id(id)
                    .phone(phone)
                    .password(encoder.encode(password))
                    .nickname(nickname == null ? "超级管理员" : nickname)
                    .avatar("")
                    .role("ADMIN")
                    .createdAt(System.currentTimeMillis())
                    .build();
            userRepository.save(admin);
            log.info("[admin-bootstrap] 已创建管理员账号 {}", phone);
        });
    }
}
