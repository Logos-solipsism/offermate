package com.offermate.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    /** HS256 的密钥下限（字节）：短于这个长度 JJWT 签发自会抛错，提前到启动期报错更容易定位 */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * 历史上作为兜底写死在这里的密钥：公开在仓库里，谁都能拿来伪造 token。
     * 保留常量只为一件事——命中它就在启动期失败，而不是"看起来配了、其实是公开值"。
     */
    static final String INSECURE_DEFAULT_SECRET = "offermate-dev-secret-key-please-change-in-production-32bytes";

    /** 不给内置默认值：未配置就是空，落到 validateSecret 的明确报错 */
    @Value("${offermate.jwt.secret:}")
    private String secret;

    @Value("${offermate.jwt.expire-hours:24}")
    private long expireHours;

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("offermate.jwt.secret 未配置（没有内置默认值）："
                    + "请设置环境变量 JWT_SECRET，提供至少 " + MIN_SECRET_BYTES + " 字节的随机密钥");
        }
        if (INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException("offermate.jwt.secret 仍是仓库里公开的默认密钥："
                    + "该值任何人都能拿到，等于任何人可伪造 token；请用环境变量 JWT_SECRET 换成自己的随机密钥");
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("offermate.jwt.secret 只有 " + length + " 字节，HS256 要求至少 "
                    + MIN_SECRET_BYTES + " 字节；请用环境变量 JWT_SECRET 提供更长的随机密钥");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3600_000L))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
