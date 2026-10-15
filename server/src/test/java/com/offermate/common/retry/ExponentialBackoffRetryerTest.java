package com.offermate.common.retry;

import com.offermate.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指数退避重试：次数上限、抖动范围、封顶行为。 */
class ExponentialBackoffRetryerTest {

    private ExponentialBackoffRetryer retryer(int maxAttempts, long base, long cap) {
        ExponentialBackoffRetryer retryer = new ExponentialBackoffRetryer();
        ReflectionTestUtils.setField(retryer, "maxAttempts", maxAttempts);
        ReflectionTestUtils.setField(retryer, "baseBackoffMillis", base);
        ReflectionTestUtils.setField(retryer, "maxBackoffMillis", cap);
        return retryer;
    }

    @Test
    @DisplayName("第一次就成功时不会触发重试")
    void successWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        String result = retryer(4, 1, 2).execute("测试", () -> {
            calls.incrementAndGet();
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("前几次失败后会重试，并最终返回成功结果")
    void retryUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = retryer(4, 1, 2).execute("三方解析", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("connection reset");
            }
            return "parsed";
        });
        assertEquals("parsed", result);
        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("一直失败时重试到上限后抛出业务异常")
    void failAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        BizException error = assertThrows(BizException.class, () -> retryer(3, 1, 2).execute("三方解析", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(3, calls.get());
        assertTrue(error.getMessage().contains("三方解析"));
    }

    @Test
    @DisplayName("退避时间按指数增长，且封顶后不再增长")
    void backoffGrowsThenCapped() {
        ExponentialBackoffRetryer retryer = retryer(4, 1000, 5000);
        long first = (Long) ReflectionTestUtils.invokeMethod(retryer, "calculateBackoff", 1);
        long capped = (Long) ReflectionTestUtils.invokeMethod(retryer, "calculateBackoff", 10);

        // 第 1 次：1000 + 抖动（抖动范围 0 ~ 500）
        assertTrue(first >= 1000 && first < 1500, "第 1 次退避应落在 [1000,1500): " + first);
        // 第 10 次：指数已经远超上限，封顶到 5000 + 抖动（0 ~ 2500），不会再继续增长
        assertTrue(capped >= 5000 && capped < 7500, "退避必须封顶在 5000 附近: " + capped);
    }
}
