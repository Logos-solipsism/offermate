package com.offermate.mq;

/**
 * Topic 规划。
 * 主链路只负责"发消息"，所有长耗时动作都在消费端完成，失败自动阶梯重试，
 * 超过最大重试次数后进入 %DLQ%cg_xxx 死信队列，由后台人工/定时重放。
 */
public final class MqTopics {

    /** 简历解析 + AI 结构化（长耗时，10s~60s 级） */
    public static final String RESUME_PARSE = "topic_resume_parse";

    /** JD 匹配打分 + 投递状态流转（秒级） */
    public static final String JOB_MATCH = "topic_job_match";

    /** 站内信 / 短信 / 邮件推送（三方调用易抖动） */
    public static final String NOTIFY = "topic_notify";

    public static final String CG_RESUME_PARSE = "cg_resume_parse";
    public static final String CG_JOB_MATCH = "cg_job_match";
    public static final String CG_NOTIFY = "cg_notify";

    public static final String DLQ_RESUME_PARSE = "%DLQ%" + CG_RESUME_PARSE;

    private MqTopics() {
    }
}
