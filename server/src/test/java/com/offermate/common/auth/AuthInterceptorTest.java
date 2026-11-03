package com.offermate.common.auth;

import com.offermate.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 拦截器是整条鉴权链路的闸门：这里钉住会话校验——存的 token 必须与请求携带的完全一致。 */
class AuthInterceptorTest {

    private static final String SECRET = "offermate-test-secret-key-0123456789abcdef";
    private static final long USER_ID = 8L;
    private static final String SESSION_KEY = "session:token:" + USER_ID;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private AuthInterceptor interceptor() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 24L);
        when(redis.opsForValue()).thenReturn(valueOps);
        return new AuthInterceptor(jwtUtil, redis);
    }

    private String issueToken() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 24L);
        return jwtUtil.generate(USER_ID, "SEEKER");
    }

    /** 记录响应体，用来断言 401 与提示语 */
    private HttpServletResponse response(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));
        return response;
    }

    private HttpServletRequest request(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");
        return request;
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("会话里存的 token 与请求一致时放行，并滑动续期")
    void matchingSessionTokenPasses() throws Exception {
        String token = issueToken();
        when(valueOps.get(SESSION_KEY)).thenReturn(token);
        StringWriter body = new StringWriter();

        boolean passed = interceptor().preHandle(request(token), response(body), new Object());

        assertTrue(passed);
        assertEquals(USER_ID, UserContext.userId());
        assertTrue(body.toString().isEmpty());
        verify(redis).expire(eq(SESSION_KEY), eq(24L), any());
    }

    @Test
    @DisplayName("同一账号重新登录后，旧 token 因为会话值不匹配而被拒绝")
    void staleTokenAfterReloginRejected() throws Exception {
        String oldToken = issueToken();
        // 重新登录：会话里换成了新签发的令牌
        when(valueOps.get(SESSION_KEY)).thenReturn("newly-issued-token");
        StringWriter body = new StringWriter();

        boolean passed = interceptor().preHandle(request(oldToken), response(body), new Object());

        assertFalse(passed);
        assertNull(UserContext.userId());
        assertTrue(body.toString().contains("401"));
        verify(redis, never()).expire(anyString(), any(Long.class), any());
    }

    @Test
    @DisplayName("会话已不存在（被踢下线）时拒绝")
    void missingSessionRejected() throws Exception {
        String token = issueToken();
        when(valueOps.get(SESSION_KEY)).thenReturn(null);
        StringWriter body = new StringWriter();

        boolean passed = interceptor().preHandle(request(token), response(body), new Object());

        assertFalse(passed);
        assertNull(UserContext.userId());
        assertTrue(body.toString().contains("401"));
    }

    @Test
    @DisplayName("没有 Authorization 头时直接 401，不去查 Redis")
    void missingTokenRejected() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        StringWriter body = new StringWriter();

        boolean passed = interceptor().preHandle(request, response(body), new Object());

        assertFalse(passed);
        assertTrue(body.toString().contains("401"));
        verify(valueOps, never()).get(anyString());
    }
}
