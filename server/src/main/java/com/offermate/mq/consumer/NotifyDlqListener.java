package com.offermate.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.trace.TraceIdFilter;
import com.offermate.common.trace.TraceUtil;
import com.offermate.mq.MqTopics;
import com.offermate.mq.entity.MqConsumeLog;
import com.offermate.mq.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 通知推送的死信消费者。
 *
 * <p>消息耗尽阶梯重试后会进 {@code DLQ_NOTIFY}。在此之前这两个主题没有任何消费者，
 * 等于进黑洞：看板上查不到、也没人知道丢了什么。这里把它落库标记为 DEAD，
 * 让后台看板能看到，之后按业务键重投即可（站内信按 msgId 幂等，重投不会重复发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.DLQ_NOTIFY, consumerGroup = "cg_dlq_notify")
public class NotifyDlqListener implements RocketMQListener<MessageExt> {

    private final MqConsumeLogMapper consumeLogMapper;

    @Override
    public void onMessage(MessageExt message) {
        // 把消息里带的 traceId 放回日志上下文：消费日志与产生它的那次请求用同一个 ID 串起来
        TraceUtil.start(message.getUserProperty(TraceIdFilter.HEADER));
        try {
            handle(message);
        } finally {
            TraceUtil.clear();
        }
    }

    private void handle(MessageExt message) {
        String msgId = message.getMsgId();
        log.error("通知推送进入死信队列 msgId={}, key={}, body={}", msgId, message.getKeys(), new String(message.getBody()));
        MqConsumeLog exist = consumeLogMapper.selectOne(Wrappers.<MqConsumeLog>lambdaQuery()
                .eq(MqConsumeLog::getMsgId, msgId).last("limit 1"));
        if (exist != null) {
            consumeLogMapper.updateStatus(msgId, MqConsumeLog.STATUS_DEAD, "进入死信队列，等待人工重放");
        }
    }
}
