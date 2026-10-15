package com.offermate.common.auth;

import com.offermate.common.context.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 登录拦截器：解析 JWT → 校验 Redis 会话 → 写入 UserContext。
 * 好处：① 支持服务端踢人（删除 Redis 会话即失效）；② 业务代码统一从 UserContext 取 userId，
 * 前端无法伪造身份，AI 工具层的数据权限也依赖它。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY_PREFIX = "session:token:";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = resolveToken(request);
        if (token == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\"}");
            return false;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            String sessionKey = SESSION_KEY_PREFIX + claims.getSubject();
            if (Boolean.FALSE.equals(redis.hasKey(sessionKey))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"code\":401,\"message\":\"登录已失效，请重新登录\"}");
                return false;
            }
            redis.expire(sessionKey, 24, TimeUnit.HOURS);
            UserContext.set(new UserContext.LoginUser(
                    Long.valueOf(claims.getSubject()),
                    String.valueOf(claims.get("role")),
                    clientIp(request)));
            return true;
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"code\":401,\"message\":\"token 无效\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip.split(",")[0].trim();
    }
}
