package com.offermate.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.mq.entity.MqConsumeLog;
import com.offermate.mq.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 消费幂等控制。
 *
 * 为什么不能简单地"插入失败就跳过"？
 * 因为 MQ 的重试消息与首次消息共用同一个 msgId（UNIQ_KEY），
 * 如果失败后也直接丢弃，重试就永远修不好那条消息。
 *
 * 所以规则是：
 *   1) 插入成功            → 首次消费，放行；
 *   2) 已存在且 SUCCESS    → 真重复，丢弃；
 *   3) 已存在且 FAILED     → 是重试，放行（retry_count + 1）；
 *   4) 已存在且 CONSUMING 且在并发窗口内 → 可能是并发重复投递，丢弃；
 *   5) 已存在且 CONSUMING 但超过窗口     → 上次消费进程挂了，放行重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqIdempotentRepository {

    private static final Duration CONCURRENT_WINDOW = Duration.ofMinutes(5);

    private final MqConsumeLogMapper consumeLogMapper;

    public boolean tryAcquire(String msgId, String topic, String bizKey) {
        MqConsumeLog consumeLog = new MqConsumeLog();
        consumeLog.setMsgId(msgId);
        consumeLog.setTopic(topic);
        consumeLog.setBizKey(bizKey);
        consumeLog.setStatus(MqConsumeLog.STATUS_CONSUMING);
        consumeLog.setRetryCount(0);
        consumeLog.setErrorMsg("");
        consumeLog.setCreateTime(LocalDateTime.now());
        consumeLog.setUpdateTime(LocalDateTime.now());
        try {
            consumeLogMapper.insert(consumeLog);
            return true;
        } catch (DuplicateKeyException e) {
            MqConsumeLog exist = consumeLogMapper.selectOne(Wrappers.<MqConsumeLog>lambdaQuery()
                    .eq(MqConsumeLog::getMsgId, msgId).last("limit 1"));
            if (exist == null) {
                return true;
            }
            if (MqConsumeLog.STATUS_SUCCESS.equals(exist.getStatus())) {
                log.info("消息已消费过，幂等丢弃 msgId={}", msgId);
                return false;
            }
            if (MqConsumeLog.STATUS_CONSUMING.equals(exist.getStatus())
                    && exist.getUpdateTime() != null
                    && exist.getUpdateTime().isAfter(LocalDateTime.now().minus(CONCURRENT_WINDOW))) {
                log.warn("消息正在被其他线程消费，跳过 msgId={}", msgId);
                return false;
            }
            log.info("消息重试放行 msgId={}, retryCount={}", msgId, exist.getRetryCount());
            consumeLogMapper.updateStatus(msgId, MqConsumeLog.STATUS_CONSUMING, exist.getErrorMsg());
            return true;
        }
    }

    public void markSuccess(String msgId) {
        consumeLogMapper.updateStatus(msgId, MqConsumeLog.STATUS_SUCCESS, "");
    }

    public void markFailed(String msgId, String errorMsg) {
        String message = errorMsg == null ? "unknown" : errorMsg.substring(0, Math.min(400, errorMsg.length()));
        consumeLogMapper.updateStatus(msgId, MqConsumeLog.STATUS_FAILED, message);
    }
}
