package com.offermate.modules.notification.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** 三方短信/邮件网关的 Mock：同样支持故障注入，用于验证指数退避重试。 */
@Slf4j
@Component
public class MockSmsClient {

    @Value("${offermate.mock.sms-fail-rate:0.0}")
    private double failRate;

    public void send(String phone, String content) {
        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            throw new IllegalStateException("短信网关超时: read timeout");
        }
        log.info("[MockSms] 发送短信 phone={}, content={}", phone, content);
    }
}
