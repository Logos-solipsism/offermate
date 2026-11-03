package com.offermate.common.auth;

import com.offermate.common.context.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 登录拦截器：解析 JWT → 校验 Redis 会话 → 写入 UserContext。
 * 好处：① 支持服务端踢人（删除 Redis 会话即失效）；② 业务代码统一从 UserContext 取 userId，
 * 前端无法伪造身份，AI 工具层的数据权限也依赖它。
 *
 * <p>会话校验比对的是 Redis 里存的 token 本身，而不只是 key 在不在：同一账号重新登录换发令牌后，
 * 旧令牌如果还能用，"改密码/封号即失效"就退化成了"把 key 删掉才失效"。
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
            return reject(response, "未登录或登录已过期");
        }
        try {
            Claims claims = jwtUtil.parse(token);
            String sessionKey = SESSION_KEY_PREFIX + claims.getSubject();
            String cachedToken = redis.opsForValue().get(sessionKey);
            if (cachedToken == null || !cachedToken.equals(token)) {
                return reject(response, "登录已失效，请重新登录");
            }
            redis.expire(sessionKey, 24, TimeUnit.HOURS);
            UserContext.set(new UserContext.LoginUser(
                    Long.valueOf(claims.getSubject()),
                    String.valueOf(claims.get("role")),
                    clientIp(request)));
            return true;
        } catch (Exception e) {
            return reject(response, "token 无效");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
        return false;
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
