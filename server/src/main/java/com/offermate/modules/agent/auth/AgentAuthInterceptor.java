package com.offermate.modules.agent.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Agent 接口的鉴权闸门：给外部 Python Agent 服务用的服务间调用凭证，不是用户登录态。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>只认请求头 {@code X-Agent-Token}，与 offermate.agent.service-token 比对，比对走常量时间；</li>
 *   <li>配置为空必须 <b>fail-closed</b>：没配 token 不等于不校验，而是一律 401；</li>
 *   <li>不经过用户 JWT 拦截器——服务调用没有用户登录态（WebConfig 里已把 /api/agent/** 从 JWT 链排除）。</li>
 * </ol>
 */
@Component
public class AgentAuthInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Agent-Token";

    /** 留空（默认）= 所有 /api/agent/** 一律 401，绝不因"没配"而放开 */
    @Value("${offermate.agent.service-token:}")
    private String serviceToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!matches(request.getHeader(TOKEN_HEADER))) {
            return reject(response);
        }
        return true;
    }

    /** 常量时间比较：先各自 SHA-256 成定长摘要，再逐字节比较 */
    boolean matches(String presented) {
        String expected = serviceToken;
        // fail-closed：配置缺失 / 空白时任何请求都不放行
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (presented == null) {
            return false;
        }
        // 双方先摘要再比较：长度差异本身也是信号，先抹平成定长再比，
        // 让耗时与"对上几个字符"无关，避免被逐字节爆破。
        return MessageDigest.isEqual(sha256(presented), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"agent token 无效或未配置\"}");
        return false;
    }
}
