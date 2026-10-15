package com.offermate.common.limiter;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.offermate.common.limiter.mapper.RateLimitConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 限流阈值解析：配置表优先、注解兜底、配置停用或读表失败时也要能兜住。 */
class RateLimitConfigProviderTest {

    private final RateLimitConfigMapper mapper = mock(RateLimitConfigMapper.class);

    private RateLimitConfig row(int capacity, int rate, int enabled) {
        RateLimitConfig config = new RateLimitConfig();
        config.setLimitKey("apply");
        config.setDimension("USER");
        config.setCapacity(capacity);
        config.setRate(rate);
        config.setEnabled(enabled);
        return config;
    }

    @Test
    @DisplayName("配置表有记录时用配置表的阈值")
    void useConfigWhenPresent() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row(20, 5, 1));
        RateLimitConfigProvider.Setting setting =
                new RateLimitConfigProvider(mapper).resolve("apply", "USER", 5, 1);
        assertEquals(20, setting.capacity());
        assertEquals(5, setting.rate());
        assertTrue(setting.fromConfig());
    }

    @Test
    @DisplayName("配置表没有记录时回退注解默认值")
    void fallbackToAnnotation() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        RateLimitConfigProvider.Setting setting =
                new RateLimitConfigProvider(mapper).resolve("apply", "USER", 5, 1);
        assertEquals(5, setting.capacity());
        assertEquals(1, setting.rate());
        assertFalse(setting.fromConfig());
    }

    @Test
    @DisplayName("配置被停用时回退注解默认值")
    void disabledConfigFallsBack() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row(20, 5, 0));
        RateLimitConfigProvider.Setting setting =
                new RateLimitConfigProvider(mapper).resolve("apply", "USER", 5, 1);
        assertEquals(5, setting.capacity());
        assertFalse(setting.fromConfig());
    }

    @Test
    @DisplayName("配置表读失败时不抛异常，回退注解默认值")
    void readFailureFallsBack() {
        when(mapper.selectOne(any(Wrapper.class))).thenThrow(new IllegalStateException("db down"));
        RateLimitConfigProvider.Setting setting =
                new RateLimitConfigProvider(mapper).resolve("apply", "USER", 5, 1);
        assertEquals(5, setting.capacity());
        assertEquals(1, setting.rate());
        assertFalse(setting.fromConfig());
    }
}
