package com.offermate.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqIdempotentRepository;
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
 * 死信消费者：MQ 阶梯重试 16 次仍失败的消息进入 %DLQ%，这里落库标记为 DEAD，
 * 后台看板展示 + 支持按业务键一键重放（见 MqAdminController）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqTopics.DLQ_RESUME_PARSE, consumerGroup = "cg_dlq_resume_parse")
public class ResumeParseDlqListener implements RocketMQListener<MessageExt> {

    private final MqConsumeLogMapper consumeLogMapper;

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
        log.error("简历解析进入死信队列 msgId={}, body={}", msgId, new String(message.getBody()));
        MqConsumeLog exist = consumeLogMapper.selectOne(Wrappers.<MqConsumeLog>lambdaQuery()
                .eq(MqConsumeLog::getMsgId, msgId).last("limit 1"));
        if (exist != null) {
            consumeLogMapper.updateStatus(msgId, MqConsumeLog.STATUS_DEAD, "进入死信队列，等待人工重放");
        }
    }
}
