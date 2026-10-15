package com.offermate.common.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
