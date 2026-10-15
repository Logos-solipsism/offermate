package com.offermate.common.limiter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.limiter.mapper.RateLimitConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流阈值解析：配置表优先，注解兜底。
 *
 * <p>为什么要缓存：限流是在每个请求上执行的，不能每次都查库。
 * 这里用 30 秒的本地缓存——运营改完配置最多 30 秒生效，代价是每台实例各存一份，
 * 对"改阈值"这种低频操作完全够用（真要做到秒级一致再换成 Redis 发布订阅）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitConfigProvider {

    private static final long CACHE_MILLIS = 30_000L;

    private final RateLimitConfigMapper configMapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 解析出本次限流真正使用的容量与速率。 */
    public Setting resolve(String limitKey, String dimension, int annotationCapacity, int annotationRate) {
        String cacheKey = limitKey + ":" + dimension;
        Cached cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached == null || now - cached.loadedAt() > CACHE_MILLIS) {
            cached = new Cached(load(limitKey, dimension), now);
            cache.put(cacheKey, cached);
        }
        Setting fromDb = cached.setting();
        if (fromDb == null) {
            return new Setting(annotationCapacity, annotationRate, false);
        }
        return fromDb;
    }

    private Setting load(String limitKey, String dimension) {
        try {
            RateLimitConfig config = configMapper.selectOne(Wrappers.<RateLimitConfig>lambdaQuery()
                    .eq(RateLimitConfig::getLimitKey, limitKey)
                    .eq(RateLimitConfig::getDimension, dimension)
                    .last("limit 1"));
            if (config == null || config.getEnabled() == null || config.getEnabled() != 1
                    || config.getCapacity() == null || config.getRate() == null) {
                return null;
            }
            return new Setting(config.getCapacity(), config.getRate(), true);
        } catch (Exception e) {
            // 配置表读不到不能让业务跟着挂：回退注解默认值，并留一条日志
            log.warn("读取限流配置失败，回退注解默认值: key={}, dimension={}, cause={}", limitKey, dimension, e.getMessage());
            return null;
        }
    }

    private record Cached(Setting setting, long loadedAt) {
    }

    /** fromConfig 用来区分"阈值来自配置表"还是"来自注解兜底"。 */
    public record Setting(int capacity, int rate, boolean fromConfig) {
    }
}
