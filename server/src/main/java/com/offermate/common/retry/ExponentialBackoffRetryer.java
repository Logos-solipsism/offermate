package com.offermate.common.retry;

import com.offermate.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 指数退避重试器：退避时间 = min(maxBackoff, base * 2^(attempt-1)) + 随机抖动。
 *
 * 适用场景：三方解析 / 短信 / 大模型 API 这类"百毫秒级、可同步阻塞等待"的调用。
 * 注意：分钟级的长任务重试不要用本类（会占住业务线程），应交给 RocketMQ 延时消息
 * 或定时任务调度，本类的失败结果同样会被投递到 MQ 做阶梯重试。
 *
 * 抖动（jitter）的作用：避免大量失败请求在同一时刻集体重试，形成重试风暴。
 */
@Slf4j
@Component
public class ExponentialBackoffRetryer {

    @Value("${offermate.retry.max-attempts:4}")
    private int maxAttempts;

    @Value("${offermate.retry.base-backoff-millis:200}")
    private long baseBackoffMillis;

    @Value("${offermate.retry.max-backoff-millis:5000}")
    private long maxBackoffMillis;

    public <T> T execute(String bizDesc, Supplier<T> action) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.info("[{}] 第 {} 次重试成功", bizDesc, attempt - 1);
                }
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt == maxAttempts) {
                    break;
                }
                long backoff = calculateBackoff(attempt);
                log.warn("[{}] 第 {} 次调用失败: {}, {}ms 后重试", bizDesc, attempt, e.getMessage(), backoff);
                sleep(backoff);
            }
        }
        throw new BizException("[" + bizDesc + "] 重试 " + maxAttempts + " 次后仍失败: "
                + (lastError == null ? "unknown" : lastError.getMessage()));
    }

    public void execute(String bizDesc, Runnable action) {
        execute(bizDesc, () -> {
            action.run();
            return null;
        });
    }

    private long calculateBackoff(int attempt) {
        long exponential = baseBackoffMillis << (attempt - 1);
        long capped = Math.min(maxBackoffMillis, exponential);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, capped / 2));
        return capped + jitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("重试被中断");
        }
    }
}
