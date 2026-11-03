package com.offermate.modules.agent.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 闸门的三条底线：token 对了才放行、token 错了 401、<b>没配 token 时也必须 401</b>。
 * 最后一条是本任务的重点：配置缺失绝不能退化成"不校验"。
 */
class AgentAuthInterceptorTest {

    private static final String TOKEN = "agent-service-token-for-test-0123456789";

    private AgentAuthInterceptor interceptor(String serviceToken) {
        AgentAuthInterceptor target = new AgentAuthInterceptor();
        ReflectionTestUtils.setField(target, "serviceToken", serviceToken);
        return target;
    }

    private HttpServletRequest request(String header) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(AgentAuthInterceptor.TOKEN_HEADER)).thenReturn(header);
        return request;
    }

    @Test
    @DisplayName("token 正确时放行")
    void correctTokenPasses() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor(TOKEN).preHandle(request(TOKEN), response, new Object());

        assertTrue(passed);
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().isEmpty());
    }

    @Test
    @DisplayName("token 错误时 401")
    void wrongTokenRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor(TOKEN).preHandle(request("wrong-token"), response, new Object());

        assertFalse(passed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("401"));
    }

    @Test
    @DisplayName("未配置 token 时 401：fail-closed，空配置绝不等于放开")
    void unconfiguredTokenRejected() throws Exception {
        for (String blank : new String[]{null, "", "   "}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean passed = interceptor(blank).preHandle(request(TOKEN), response, new Object());

            assertFalse(passed, "service-token 未配置时必须拒绝");
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    @DisplayName("未配置 token 且请求也不带 token 时同样 401")
    void unconfiguredTokenAndMissingHeaderRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor(null).preHandle(request(null), response, new Object());

        assertFalse(passed);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("已配置 token 但请求不带该请求头时 401，且不去比对空串")
    void missingHeaderRejected() throws Exception {
        HttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor(TOKEN).preHandle(request(null), response, new Object());

        assertFalse(passed);
        assertEquals(401, response.getStatus());
    }
}
