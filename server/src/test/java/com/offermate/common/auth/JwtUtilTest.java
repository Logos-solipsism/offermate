package com.offermate.common.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 签发与校验：身份链路的入口，权限相关的东西必须有测试兜底。 */
class JwtUtilTest {

    private static final String SECRET = "offermate-test-secret-key-0123456789abcdef";

    private JwtUtil jwtUtil(String secret, long expireHours) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "expireHours", expireHours);
        return util;
    }

    @Test
    @DisplayName("签发的 token 能解析出用户 ID 与角色")
    void generateAndParse() {
        JwtUtil util = jwtUtil(SECRET, 24);
        Claims claims = util.parse(util.generate(8L, "SEEKER"));
        assertEquals("8", claims.getSubject());
        assertEquals("SEEKER", claims.get("role"));
    }

    @Test
    @DisplayName("换密钥签发的 token 校验不通过")
    void wrongSecretRejected() {
        String token = jwtUtil(SECRET, 24).generate(8L, "SEEKER");
        JwtUtil other = jwtUtil("another-secret-key-long-enough-for-hmac-123456", 24);
        assertThrows(Exception.class, () -> other.parse(token));
    }

    @Test
    @DisplayName("被篡改的 token 校验不通过")
    void tamperedRejected() {
        JwtUtil util = jwtUtil(SECRET, 24);
        String token = util.generate(8L, "SEEKER");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("aa") ? "bb" : "aa");
        assertThrows(Exception.class, () -> util.parse(tampered));
    }

    @Test
    @DisplayName("过期 token 校验不通过")
    void expiredRejected() {
        JwtUtil util = jwtUtil(SECRET, -1);
        String expired = util.generate(8L, "SEEKER");
        assertThrows(Exception.class, () -> util.parse(expired));
    }

    @Test
    @DisplayName("未配置密钥时启动期就失败，而不是等到签发/解析时才炸")
    void blankSecretRejectedOnStartup() {
        assertThrows(IllegalStateException.class, () -> jwtUtil(null, 24).validateSecret());
        assertThrows(IllegalStateException.class, () -> jwtUtil("", 24).validateSecret());
        assertThrows(IllegalStateException.class, () -> jwtUtil("   ", 24).validateSecret());
    }

    @Test
    @DisplayName("仍在使用仓库里公开的默认密钥时启动期失败")
    void publicDefaultSecretRejectedOnStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> jwtUtil(JwtUtil.INSECURE_DEFAULT_SECRET, 24).validateSecret());
        assertTrue(ex.getMessage().contains("公开的默认密钥"));
    }

    @Test
    @DisplayName("密钥不足 32 字节时启动期失败")
    void shortSecretRejectedOnStartup() {
        // 差一个字节也不放过，否则要等到第一次签发 token 才炸
        String shortSecret = "k".repeat(JwtUtil.MIN_SECRET_BYTES - 1);
        assertEquals(31, shortSecret.getBytes(StandardCharsets.UTF_8).length);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> jwtUtil(shortSecret, 24).validateSecret());
        assertTrue(ex.getMessage().contains("31"));
    }

    @Test
    @DisplayName("合规密钥（恰好 32 字节）通过启动期校验")
    void validSecretPassesOnStartup() {
        String exactly32 = "0123456789abcdef0123456789abcdef";
        assertEquals(32, exactly32.getBytes(StandardCharsets.UTF_8).length);
        assertDoesNotThrow(() -> jwtUtil(exactly32, 24).validateSecret());
        assertDoesNotThrow(() -> jwtUtil(SECRET, 24).validateSecret());
    }
}
