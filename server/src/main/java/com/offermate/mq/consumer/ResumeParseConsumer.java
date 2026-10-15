package com.offermate.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.common.retry.ExponentialBackoffRetryer;
import com.offermate.modules.resume.client.ResumeParserClient;
import com.offermate.modules.resume.entity.Resume;
import com.offermate.modules.resume.mapper.ResumeMapper;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqIdempotentRepository;
import com.offermate.mq.MqProducer;
import com.offermate.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历解析消费者：把"分片合并"与"解析 + 结构化"解耦，这是投递/上传接口能做到几十毫秒返回的关键（实测单请求 20ms）。
 *
 * 可靠性链路：
 *   幂等表拦截重复 → 指数退避重试三方抖动 → 仍失败则抛异常交给 RocketMQ 阶梯重试
 *   → 16 次后进 %DLQ%cg_resume_parse → 后台按 fileId 重放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.RESUME_PARSE,
        consumerGroup = MqTopics.CG_RESUME_PARSE,
        consumeThreadMax = 32)
public class ResumeParseConsumer implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final MqIdempotentRepository idempotentRepository;
    private final ExponentialBackoffRetryer retryer;
    private final ResumeParserClient resumeParserClient;
    private final ResumeMapper resumeMapper;
    private final MqProducer mqProducer;

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
        String bizKey = message.getKeys();
        if (!idempotentRepository.tryAcquire(msgId, MqTopics.RESUME_PARSE, bizKey)) {
            return;
        }
        try {
            MqEvents.ResumeParseEvent event = objectMapper.readValue(message.getBody(), MqEvents.ResumeParseEvent.class);
            Map<String, Object> parsed = retryer.execute("简历解析",
                    () -> resumeParserClient.parse(event.objectKey()));

            Resume resume = resumeMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<Resume>lambdaQuery()
                            .eq(Resume::getFileId, event.fileId()).last("limit 1"));
            if (resume == null) {
                resume = new Resume();
                resume.setUserId(event.userId());
                resume.setFileId(event.fileId());
                resume.setTitle(event.fileName());
                resume.setIsDefault(1);
                resume.setParseRetry(0);
                resume.setParsedJson(objectMapper.writeValueAsString(parsed));
                resume.setParseStatus(Resume.PARSE_DONE);
                resumeMapper.insert(resume);
            } else {
                resume.setParsedJson(objectMapper.writeValueAsString(parsed));
                resume.setParseStatus(Resume.PARSE_DONE);
                resumeMapper.updateById(resume);
            }

            idempotentRepository.markSuccess(msgId);
            mqProducer.sendAsync(MqTopics.NOTIFY, "resume:" + event.fileId(),
                    new MqEvents.NotifyEvent(event.userId(), event.fileId(), "SYSTEM",
                            "简历解析完成", "你的简历已解析完成，可以去投递啦"));
            log.info("简历解析成功 fileId={}, resumeId={}", event.fileId(), resume.getId());
        } catch (Exception e) {
            // 记录失败后抛出：让 RocketMQ 走阶梯重试，超限进死信，绝不在业务线程里 sleep 等待
            idempotentRepository.markFailed(msgId, e.getMessage());
            log.error("简历解析失败，交由 MQ 阶梯重试 msgId={}", msgId, e);
            throw new RuntimeException(e);
        }
    }
}
