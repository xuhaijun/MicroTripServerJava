package com.microtrip.server.service;

import com.microtrip.server.common.BizException;
import com.microtrip.server.domain.User;
import com.microtrip.server.dto.AuthResponse;
import com.microtrip.server.dto.LoginRequest;
import com.microtrip.server.dto.RegisterRequest;
import com.microtrip.server.dto.UserDto;
import com.microtrip.server.repository.TrajectoryPointRepository;
import com.microtrip.server.repository.TrajectoryRepository;
import com.microtrip.server.repository.UserRepository;
import com.microtrip.server.revocation.RevocationService;
import com.microtrip.server.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * 用户认证业务。
 *
 * <p>协议与 App 端 AuthService（Phase 4）对齐：
 * 注册/登录返回 {@code { token, user }}；登出在 JWT 无状态基础上<b>真正吊销</b>当前令牌
 * （写入 {@link RevocationService} 黑名单，TTL = 令牌剩余有效期），使该令牌立即失效。</p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final TrajectoryRepository trajectoryRepository;
    private final TrajectoryPointRepository pointRepository;
    private final JwtUtil jwtUtil;
    private final RevocationService revocation;
    private final PasswordEncoder encoder; // cost 10，与 Node 版 bcryptjs 一致

    public UserService(UserRepository userRepository, TrajectoryRepository trajectoryRepository,
                       TrajectoryPointRepository pointRepository,
                       JwtUtil jwtUtil, RevocationService revocation,
                       PasswordEncoder encoder) {
        this.userRepository = userRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.pointRepository = pointRepository;
        this.jwtUtil = jwtUtil;
        this.revocation = revocation;
        this.encoder = encoder;
    }

    /** 手机号格式：11 位，1 开头 */
    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^1\\d{10}$");

    public boolean isValidPhone(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (!isValidPhone(req.phone())) {
            throw BizException.badRequest("请输入 11 位手机号");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw BizException.badRequest("密码至少 6 位");
        }
        if (req.nickname() == null || req.nickname().trim().isEmpty()) {
            throw BizException.badRequest("请输入昵称");
        }
        if (userRepository.existsByPhone(req.phone())) {
            throw BizException.conflict("该手机号已注册，请直接登录");
        }

        String id = genUserId();
        String now = String.valueOf(System.currentTimeMillis());
        User user = User.builder()
                .id(id)
                .phone(req.phone())
                .password(encoder.encode(req.password()))
                .nickname(req.nickname().trim())
                .avatar("")
                .role("USER")
                .createdAt(Long.parseLong(now))
                .build();
        userRepository.save(user);

        String token = jwtUtil.sign(user.getId(), user.getNickname(), user.getPhone(), user.getRole());
        return new AuthResponse(token, UserDto.from(user));
    }

    public AuthResponse login(LoginRequest req) {
        if (!isValidPhone(req.phone())) {
            throw BizException.badRequest("请输入 11 位手机号");
        }
        if (req.password() == null || req.password().isEmpty()) {
            throw BizException.badRequest("请输入密码");
        }
        User user = userRepository.findByPhone(req.phone())
                .orElseThrow(() -> BizException.unauthorized("账号不存在，请先注册"));
        if (!encoder.matches(req.password(), user.getPassword())) {
            throw BizException.unauthorized("密码错误，请重试");
        }
        String token = jwtUtil.sign(user.getId(), user.getNickname(), user.getPhone(), user.getRole());
        return new AuthResponse(token, UserDto.from(user));
    }

    /**
     * 登出：吊销当前令牌（写入黑名单，TTL = 剩余有效期）。
     * 即使 JWT 本身未过期，吊销后过滤链会拒绝该令牌，实现「主动登出」。
     *
     * @param token 原始 Bearer token（可为 null，此时仅返回成功，不做吊销）
     */
    public boolean logout(String token) {
        if (token != null && !token.isBlank()) {
            long ttl = jwtUtil.ttlSeconds(token);
            revocation.revokeToken(token, ttl);
        }
        return true;
    }

    /**
     * 注销账号：删除该用户及其云端轨迹，并吊销当前令牌。
     *
     * <p>合规要求（小米等渠道强制）：账号注销必须**真正删除服务端数据**，
     * 而非仅退出登录。为避免「已注销仍能用旧 token 调接口」，
     * 删除后同步把当前令牌写入 {@link RevocationService} 黑名单。</p>
     *
     * <p>整个过程在一个事务内：GPS 点 → 轨迹 → 账号三张表的删除同成败，避免遗留孤儿数据。</p>
     *
     * <p><b>删除顺序不可颠倒</b>：{@code trajectory_points} 的清理依赖
     * {@code trajectories} 的归属关系（JPQL 子查询），若先删轨迹就再也定位不到那些点，
     * 会永久残留孤儿行（既占空间，也让「注销即彻底删除」的合规承诺落空）。</p>
     *
     * @param userId 当前登录用户 ID（来自 JWT subject）
     * @param token  原始 Bearer token（用于吊销，可为 null）
     * @return true 表示注销完成
     */
    @Transactional
    public boolean deleteAccount(String userId, String token) {
        if (userId == null || userId.isBlank()) {
            throw BizException.unauthorized("未获取到登录用户");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BizException.unauthorized("账号不存在或已注销"));

        // 1) GPS 点子表（必须先于轨迹，见上方顺序说明）
        pointRepository.deleteAllByUserId(user.getId());
        // 2) 云端轨迹
        trajectoryRepository.deleteAllByUserId(user.getId());
        // 3) 账号本身
        userRepository.delete(user);

        // 吊销当前令牌：注销后旧 token 立即失效
        logout(token);
        return true;
    }

    /** 生成用户 ID（u_ + 12 字节随机 hex，避免自增泄露用户量） */
    private String genUserId() {
        byte[] raw = new byte[12];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder("u_");
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
