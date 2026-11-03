package com.offermate.common.auth;

import com.offermate.common.context.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>客户端 IP 默认只取 TCP 对端地址：X-Forwarded-For 是客户端可随手编的请求头，
 * 只有直连方确实是可信代理（配了 offermate.auth.trusted-proxies）时才采信它。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY_PREFIX = "session:token:";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    /**
     * 可信代理地址，逗号分隔；留空（默认）= 不信任任何转发头。
     * 只有请求的直连对端在这个列表里时，才读取它写的 X-Forwarded-For 第一段。
     */
    @Value("${offermate.auth.trusted-proxies:}")
    private String trustedProxies;

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

    /** 默认取 TCP 对端地址；仅当对端是显式配置的可信代理时，才采信 X-Forwarded-For 的第一段 */
    String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        return forwarded.split(",")[0].trim();
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (trustedProxies == null || trustedProxies.isBlank() || remoteAddr == null) {
            return false;
        }
        for (String proxy : trustedProxies.split(",")) {
            if (proxy.trim().equals(remoteAddr)) {
                return true;
            }
        }
        return false;
    }
}
