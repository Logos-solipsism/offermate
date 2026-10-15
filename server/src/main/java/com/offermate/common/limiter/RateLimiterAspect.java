package com.offermate.common.limiter;

import com.offermate.common.context.UserContext;
import com.offermate.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 限流切面：把 @RateLimit 注解翻译成令牌桶的 key 与参数。
 * key 组合规则：rate:{业务键}:{维度}:{身份}，例如 rate:apply:USER:10086
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimiterAspect {

    private final RedisTokenBucketLimiter limiter;

    @Around("@annotation(com.offermate.common.limiter.RateLimit)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        RateLimit annotation = method.getAnnotation(RateLimit.class);

        String identity = switch (annotation.dimension()) {
            case USER -> String.valueOf(UserContext.userId() == null ? "anonymous" : UserContext.userId());
            case IP -> {
                UserContext.LoginUser loginUser = UserContext.get();
                yield loginUser == null ? "unknown-ip" : String.valueOf(loginUser.clientIp());
            }
            case API -> "global";
        };

        String bucketKey = "rate:" + annotation.key() + ":" + annotation.dimension() + ":" + identity;
        boolean allowed = limiter.tryAcquire(bucketKey, annotation.capacity(), annotation.rate(), annotation.tokens());
        if (!allowed) {
            log.warn("触发限流, key={}, method={}", bucketKey, method.getName());
            throw BizException.tooManyRequests(annotation.message());
        }
        return point.proceed();
    }
}
