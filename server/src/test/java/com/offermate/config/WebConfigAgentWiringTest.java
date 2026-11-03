package com.offermate.config;

import com.offermate.common.auth.AuthInterceptor;
import com.offermate.modules.agent.auth.AgentAuthInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 钉住 WebConfig 的接线：/api/agent/** 必须从用户 JWT 拦截器里摘出去，改由 AgentAuthInterceptor 守。
 * 这条写漏了，Agent 接口要么永远 401（被 JWT 拦），要么根本不鉴权——两种都是 P0。
 */
class WebConfigAgentWiringTest {

    /** 拦截器 → 它被登记的路径 / 排除的路径，按注册顺序记录 */
    private final Map<HandlerInterceptor, Paths> registered = new LinkedHashMap<>();

    private static final class Paths {
        final List<String> added = new ArrayList<>();
        final List<String> excluded = new ArrayList<>();
    }

    private void wire() {
        AuthInterceptor authInterceptor = mock(AuthInterceptor.class);
        AgentAuthInterceptor agentAuthInterceptor = new AgentAuthInterceptor();
        InterceptorRegistry registry = new InterceptorRegistry() {
            @Override
            public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
                Paths paths = new Paths();
                registered.put(interceptor, paths);
                InterceptorRegistration registration = mock(InterceptorRegistration.class, invocation -> {
                    record(invocation, "addPathPatterns", paths.added);
                    record(invocation, "excludePathPatterns", paths.excluded);
                    // 链式调用：把 mock 自己还回去
                    return invocation.getMock();
                });
                return registration;
            }
        };
        new WebConfig(authInterceptor, agentAuthInterceptor).addInterceptors(registry);
    }

    /** 两个方法都是 String... 变参：Mockito 可能把它们摊平成一个参数，也可能包成数组，两种都接 */
    private static void record(InvocationOnMock invocation, String method, List<String> sink) {
        if (!method.equals(invocation.getMethod().getName())) {
            return;
        }
        for (Object argument : invocation.getArguments()) {
            if (argument instanceof String[] array) {
                sink.addAll(List.of(array));
            } else if (argument instanceof String pattern) {
                sink.add(pattern);
            }
        }
    }

    private Paths pathsOf(Class<?> interceptorType) {
        return registered.entrySet().stream()
                .filter(e -> interceptorType.isInstance(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有注册 " + interceptorType.getSimpleName()));
    }

    @Test
    @DisplayName("只注册两个拦截器，且用户 JWT 的把 /api/agent/** 排除掉（既有规则不动）")
    void userJwtInterceptorExcludesAgentPaths() {
        wire();

        assertEquals(2, registered.size(), "只应有用户 JWT + Agent 两个拦截器");
        Paths auth = pathsOf(AuthInterceptor.class);
        assertTrue(auth.added.contains("/api/**"), "既有 /api/** 规则必须保留");
        assertTrue(auth.added.equals(List.of("/api/**")), "既有登记的路径不应被改动");
        assertTrue(auth.excluded.contains("/api/agent/**"), "/api/agent/** 必须从 JWT 拦截器里摘出去");
        assertTrue(auth.excluded.containsAll(List.of("/api/auth/**", "/doc.html", "/webjars/**", "/v3/api-docs/**")),
                "既有放行规则必须原样保留");
    }

    @Test
    @DisplayName("AgentAuthInterceptor 挂在 /api/agent/** 上，且不加任何排除规则（fail-closed 前提）")
    void agentInterceptorCoversAgentPaths() {
        wire();

        Paths agent = pathsOf(AgentAuthInterceptor.class);
        assertEquals(List.of("/api/agent/**"), agent.added);
        assertTrue(agent.excluded.isEmpty(), "闸门上加排除规则就等于开后门");
        assertFalse(pathsOf(AuthInterceptor.class).added.contains("/api/agent/**"));
    }

    @Test
    @DisplayName("注册顺序不变：既有用户 JWT 规则在前，新增 Agent 规则在后")
    void registrationOrderPreserved() {
        wire();

        HandlerInterceptor[] order = registered.keySet().toArray(new HandlerInterceptor[0]);
        assertTrue(order[0] instanceof AuthInterceptor);
        assertTrue(order[1] instanceof AgentAuthInterceptor);
    }
}
