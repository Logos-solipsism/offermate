package com.offermate.common.limiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis + Lua 的令牌桶限流器。
 * 要点：
 * 1) 补充令牌、扣减、回写、续期在 Lua 中原子完成，避免 GET 后再 SET 的并发超发；
 * 2) 桶 key 带 PEXPIRE，空闲桶自动回收，不会撑爆 Redis；
 * 3) Redis 不可用时降级为本地单机令牌桶（宁可轻微超发，也不能熔断业务）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTokenBucketLimiter {

    private static final RedisScript<List> TOKEN_BUCKET_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/token_bucket.lua"), List.class);

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${offermate.rate-limit.local-fallback-qps:50}")
    private int localFallbackQps;

    private final Map<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(String bucketKey, int capacity, int rate, int need) {
        try {
            List<?> result = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    Collections.singletonList(bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(rate),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(need));
            if (result == null || result.isEmpty()) {
                return true;
            }
            return Long.parseLong(String.valueOf(result.get(0))) == 1L;
        } catch (Exception e) {
            // 降级：Redis 挂了也要保证业务可用
            log.warn("令牌桶降级为本地限流, bucketKey={}, cause={}", bucketKey, e.getMessage());
            return localBuckets.computeIfAbsent(bucketKey, k -> new LocalBucket(localFallbackQps)).tryAcquire();
        }
    }

    /** 单机降级令牌桶：纳秒时间戳 + 令牌数，CAS 更新。 */
    private static class LocalBucket {
        private final int qps;
        private final AtomicLong tokens;
        private volatile long lastRefillNanos = System.nanoTime();

        LocalBucket(int qps) {
            this.qps = Math.max(1, qps);
            this.tokens = new AtomicLong(this.qps);
        }

        boolean tryAcquire() {
            long now = System.nanoTime();
            long delta = now - lastRefillNanos;
            if (delta > 1_000_000_000L) {
                synchronized (this) {
                    if (now - lastRefillNanos > 1_000_000_000L) {
                        tokens.set(qps);
                        lastRefillNanos = now;
                    }
                }
            }
            while (true) {
                long current = tokens.get();
                if (current <= 0) {
                    return false;
                }
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }
    }
}
