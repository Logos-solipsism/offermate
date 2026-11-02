package com.offermate.modules.auth.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.auth.JwtUtil;
import com.offermate.common.exception.BizException;
import com.offermate.common.limiter.RateLimit;
import com.offermate.common.result.R;
import com.offermate.modules.auth.entity.SysUser;
import com.offermate.modules.auth.mapper.SysUserMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_KEY_PREFIX = "session:token:";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @PostMapping("/register")
    public R<Long> register(@RequestBody @Validated RegisterRequest request) {
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, request.phone()));
        if (count > 0) {
            throw new BizException("手机号已注册");
        }
        SysUser user = new SysUser();
        user.setPhone(request.phone());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setRole(SysUser.ROLE_SEEKER); // 注册一律为求职者：HR/管理员账号由 db/data.sql 预置，不能由客户端声明
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.insert(user);
        return R.ok(user.getId());
    }

    /** 登录接口按 IP 维度限流，防止撞库 */
    @RateLimit(key = "login", capacity = 20, rate = 5, dimension = RateLimit.Dimension.IP, message = "登录过于频繁")
    @PostMapping("/login")
    public R<LoginResult> login(@RequestBody @Validated LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, request.phone()).last("limit 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException("手机号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException("账号已被禁用");
        }
        String token = jwtUtil.generate(user.getId(), user.getRole());
        // 会话落 Redis：支持服务端踢人、续期与限流身份识别
        redis.opsForValue().set(SESSION_KEY_PREFIX + user.getId(), token, 24, TimeUnit.HOURS);
        return R.ok(new LoginResult(token, user.getId(), user.getNickname(), user.getRole()));
    }

    public record RegisterRequest(@NotBlank String phone, @NotBlank String password,
                                  String nickname) {
    }

    public record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    public record LoginResult(String token, Long userId, String nickname, String role) {
    }
}
