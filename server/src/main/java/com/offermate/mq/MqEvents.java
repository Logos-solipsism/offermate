package com.offermate.mq;

import java.io.Serializable;

/** 所有 MQ 事件定义：字段保持精简，便于重放时按业务表重建。 */
public final class MqEvents {

    private MqEvents() {
    }

    /** 简历解析事件：分片合并成功后投递 */
    public record ResumeParseEvent(Long fileId,
                                   Long userId,
                                   String objectKey,
                                   String md5,
                                   String fileName,
                                   int attempt) implements Serializable {
    }

    /** 投递匹配事件：投递落库后投递，异步算匹配分并写状态 */
    public record JobMatchEvent(Long applicationId,
                                Long userId,
                                Long jobId,
                                Long resumeId,
                                String resumeMd5) implements Serializable {
    }

    /** 通知事件：站内信 + 三方短信/邮件（三方失败走指数退避重试） */
    public record NotifyEvent(Long userId,
                              Long bizId,
                              String type,
                              String title,
                              String content) implements Serializable {
    }
}
