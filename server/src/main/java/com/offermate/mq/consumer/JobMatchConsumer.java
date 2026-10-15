package com.offermate.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import com.offermate.modules.resume.entity.Resume;
import com.offermate.modules.resume.mapper.ResumeMapper;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqIdempotentRepository;
import com.offermate.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JD 匹配打分消费者：把"算分"这种可以慢一点、但不能拖慢投递接口的逻辑放到异步链路。
 * 打分策略刻意做得简单（技能标签重合率），实习项目不需要上推荐算法，
 * 但"异步算分 + 回写 + 通知"的链路是真实的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
// 注意：RocketMQ 消费线程数默认最小是 20，consumeThreadMax 不能小于它，否则启动就报 MQClientException
@RocketMQMessageListener(topic = MqTopics.JOB_MATCH, consumerGroup = MqTopics.CG_JOB_MATCH, consumeThreadMax = 32)
public class JobMatchConsumer implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final MqIdempotentRepository idempotentRepository;
    private final ApplicationMapper applicationMapper;
    private final JobMapper jobMapper;
    private final ResumeMapper resumeMapper;

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
        if (!idempotentRepository.tryAcquire(msgId, MqTopics.JOB_MATCH, message.getKeys())) {
            return;
        }
        try {
            MqEvents.JobMatchEvent event = objectMapper.readValue(message.getBody(), MqEvents.JobMatchEvent.class);
            Job job = jobMapper.selectById(event.jobId());
            Resume resume = resumeMapper.selectOne(Wrappers.<Resume>lambdaQuery()
                    .eq(Resume::getId, event.resumeId()).last("limit 1"));
            if (job == null || resume == null) {
                idempotentRepository.markSuccess(msgId);
                return;
            }

            int score = calculateScore(job.getSkills(), resume.getParsedJson());
            Application application = applicationMapper.selectById(event.applicationId());
            if (application != null) {
                application.setMatchScore(score);
                applicationMapper.updateById(application);
            }
            idempotentRepository.markSuccess(msgId);
            log.info("匹配打分完成 applicationId={}, score={}", event.applicationId(), score);
        } catch (Exception e) {
            idempotentRepository.markFailed(msgId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /** 技能标签重合度打分：交集 / 职位要求技能数，满分 100。 */
    private int calculateScore(String jobSkills, String resumeJson) {
        if (jobSkills == null || jobSkills.isBlank()) {
            return 60;
        }
        Set<String> required = Arrays.stream(jobSkills.split(","))
                .map(String::trim).map(String::toLowerCase).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (required.isEmpty()) {
            return 60;
        }
        String resumeText = resumeJson == null ? "" : resumeJson.toLowerCase();
        long hit = required.stream().filter(resumeText::contains).count();
        return (int) Math.round(hit * 100.0 / required.size());
    }
}
