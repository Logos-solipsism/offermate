package com.offermate.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 异步发送：不阻塞业务线程——投递主链路不等待任何外部调用（压测实测单请求 20ms）。
     * 生产端可靠性兜底：发送失败会告警，并由本地消息表定时任务补偿重投（见 docs/01 第 11 节）。
     */
    public void sendAsync(String topic, String bizKey, Object payload) {
        Message<String> message = buildMessage(bizKey, payload);
        rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.debug("MQ 发送成功 topic={}, key={}, msgId={}", topic, bizKey, sendResult.getMsgId());
            }

            @Override
            public void onException(Throwable e) {
                log.error("MQ 发送失败 topic={}, key={}，等待本地消息表补偿", topic, bizKey, e);
            }
        });
    }

    /** 同步发送：用于对可靠性要求更高的场景（如状态流转通知）。 */
    public SendResult sendSync(String topic, String bizKey, Object payload) {
        return rocketMQTemplate.syncSend(topic, buildMessage(bizKey, payload));
    }

    /**
     * 延时消息：阶梯重试。delayLevel 3 = 10s、4 = 30s、5 = 1m、6 = 2m...
     * 长任务重试一律走延时消息，而不是在线程里 sleep。
     */
    public void sendDelay(String topic, String bizKey, Object payload, int delayLevel) {
        rocketMQTemplate.syncSend(topic, buildMessage(bizKey, payload), 3000, delayLevel);
    }

    private Message<String> buildMessage(String bizKey, Object payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            return MessageBuilder.withPayload(body)
                    .setHeader("KEYS", bizKey)
                    // 把当前请求的 traceId 一起发出去，消费端就能用它把日志串起来
                    .setHeader(com.offermate.common.trace.TraceIdFilter.HEADER,
                            org.slf4j.MDC.get(com.offermate.common.trace.TraceIdFilter.TRACE_ID))
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("消息序列化失败: " + e.getMessage());
        }
    }
}
