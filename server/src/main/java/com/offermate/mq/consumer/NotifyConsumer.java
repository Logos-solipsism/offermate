package com.offermate.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.common.retry.ExponentialBackoffRetryer;
import com.offermate.modules.notification.client.MockSmsClient;
import com.offermate.modules.notification.entity.Notification;
import com.offermate.modules.notification.mapper.NotificationMapper;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqIdempotentRepository;
import com.offermate.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知消费者：站内信入库 + 三方短信推送。
 * 站内信是"核心动作"（必须成功），短信是"锦上添花"（重试几次仍失败就放弃并告警），
 * 这种分级处理是真实业务的常见做法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.NOTIFY, consumerGroup = MqTopics.CG_NOTIFY, consumeThreadMax = 32)
public class NotifyConsumer implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final MqIdempotentRepository idempotentRepository;
    private final ExponentialBackoffRetryer retryer;
    private final NotificationMapper notificationMapper;
    private final MockSmsClient smsClient;

    @Override
    public void onMessage(MessageExt message) {
        // 消费端把消息里带的 traceId 放回日志上下文：让消费日志和产生它的那次请求用同一个 ID 串起来
        com.offermate.common.trace.TraceUtil.start(
                message.getUserProperty(com.offermate.common.trace.TraceIdFilter.HEADER));
        try {
            handle(message);
        } finally {
            com.offermate.common.trace.TraceUtil.clear();
        }
    }

    private void handle(MessageExt message) {
        String msgId = message.getMsgId();
        if (!idempotentRepository.tryAcquire(msgId, MqTopics.NOTIFY, message.getKeys())) {
            return;
        }
        try {
            MqEvents.NotifyEvent event = objectMapper.readValue(message.getBody(), MqEvents.NotifyEvent.class);

            Notification notification = new Notification();
            notification.setUserId(event.userId());
            notification.setType(event.type());
            notification.setTitle(event.title());
            notification.setContent(event.content());
            notification.setBizId(event.bizId());
            notification.setReadFlag(0);
            notification.setCreateTime(LocalDateTime.now());
            notificationMapper.insert(notification);

            // 三方调用套指数退避重试，仍然失败也不影响站内信（降级而非失败）
            try {
                retryer.execute("短信推送", () -> smsClient.send("138****0000", event.content()));
            } catch (Exception e) {
                log.warn("短信推送最终失败，仅保留站内信: {}", e.getMessage());
            }

            idempotentRepository.markSuccess(msgId);
        } catch (Exception e) {
            idempotentRepository.markFailed(msgId, e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
