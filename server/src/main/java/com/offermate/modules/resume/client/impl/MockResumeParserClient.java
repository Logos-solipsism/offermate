package com.offermate.modules.resume.client.impl;

import com.offermate.modules.resume.client.ResumeParserClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 可注入故障的解析实现（默认 0% 失败率，压测时调成 0.3 用于验证重试机制）。
 * 把 offermate.mock.parser-fail-rate 设成 0.3，就能复现"成功率 62% → 99%+"的对比数据。
 */
@Slf4j
@Component
public class MockResumeParserClient implements ResumeParserClient {

    @Value("${offermate.mock.parser-fail-rate:0.0}")
    private double failRate;

    @Value("${offermate.mock.parser-latency-millis:800}")
    private long latencyMillis;

    @Override
    public Map<String, Object> parse(String objectKey) {
        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            throw new IllegalStateException("三方解析服务抖动: connection reset by peer");
        }
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> parsed = new HashMap<>();
        parsed.put("name", "张三");
        parsed.put("education", "本科");
        parsed.put("school", "某大学");
        parsed.put("skills", List.of("Java", "SpringBoot", "Redis", "RocketMQ"));
        parsed.put("yearsOfExperience", 0);
        parsed.put("sourceObject", objectKey);
        log.debug("简历解析完成 objectKey={}", objectKey);
        return parsed;
    }
}
