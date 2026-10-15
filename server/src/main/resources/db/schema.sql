-- OfferMate 数据库初始化脚本
CREATE DATABASE IF NOT EXISTS offermate DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE offermate;

-- 用户表（求职者 / HR / 运营）
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码',
    nickname    VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '昵称',
    role        VARCHAR(20)  NOT NULL DEFAULT 'SEEKER' COMMENT 'SEEKER/HR/ADMIN',
    company_id  BIGINT       NULL COMMENT 'HR 所属公司',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB COMMENT '用户表';

-- 职位表
CREATE TABLE IF NOT EXISTS job (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    hr_id        BIGINT       NOT NULL COMMENT '发布人',
    title        VARCHAR(100) NOT NULL COMMENT '职位名称',
    city         VARCHAR(50)  NOT NULL COMMENT '城市',
    salary_min   INT          NOT NULL DEFAULT 0 COMMENT '月薪下限(K)',
    salary_max   INT          NOT NULL DEFAULT 0 COMMENT '月薪上限(K)',
    skills       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '技能标签,逗号分隔',
    jd           TEXT         NULL COMMENT '职位描述',
    head_count   INT          NOT NULL DEFAULT 1 COMMENT '招聘人数',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1在招 0关闭',
    audit_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核 1通过 2驳回',
    view_count   INT          NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_city_status (city, status, audit_status),
    KEY idx_hr (hr_id)
) ENGINE = InnoDB COMMENT '职位表';

-- 简历文件表：md5 唯一索引是"内容级去重/秒传"的最终兜底
CREATE TABLE IF NOT EXISTS resume_file (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    md5         CHAR(32)     NOT NULL COMMENT '文件内容指纹,秒传与去重依据',
    file_name   VARCHAR(255) NOT NULL,
    object_key  VARCHAR(255) NOT NULL COMMENT 'MinIO 对象路径',
    size        BIGINT       NOT NULL DEFAULT 0 COMMENT '字节',
    upload_id   VARCHAR(128) NULL COMMENT 'MinIO 分片上传 uploadId',
    status      VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING' COMMENT 'UPLOADING/MERGING/DONE/FAILED',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 复合唯一：同一份内容每个用户最多一条记录（跨用户可以共用同一个存储对象）
    UNIQUE KEY uk_md5_user (md5, user_id),
    KEY idx_upload_id (upload_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT '简历文件表(内容级去重/秒传)';

-- 简历表（结构化档案，多版本）
CREATE TABLE IF NOT EXISTS resume (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    file_id      BIGINT       NOT NULL COMMENT '关联 resume_file',
    title        VARCHAR(100) NOT NULL DEFAULT '我的简历',
    parsed_json  JSON         NULL COMMENT '异步解析后的结构化结果',
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSING/DONE/FAILED',
    parse_retry  INT          NOT NULL DEFAULT 0 COMMENT '解析重试次数',
    is_default   TINYINT      NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_parse_status (parse_status)
) ENGINE = InnoDB COMMENT '简历档案表';

-- 投递表：uk_user_job 是"重复投递拦截"的最终兜底
CREATE TABLE IF NOT EXISTS application (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    job_id      BIGINT      NOT NULL,
    resume_id   BIGINT      NOT NULL,
    resume_md5  CHAR(32)    NOT NULL COMMENT '投递时简历内容指纹,用于内容级去重',
    status      VARCHAR(20) NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED/VIEWED/INTERVIEW/OFFER/REJECTED/WITHDRAWN',
    hr_feedback VARCHAR(500) NOT NULL DEFAULT '',
    match_score INT         NOT NULL DEFAULT 0 COMMENT 'JD 匹配分(异步计算)',
    deleted     TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_job (user_id, job_id),
    KEY idx_job_status (job_id, status),
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB COMMENT '投递表';

-- 通知表（MQ 异步落库）
CREATE TABLE IF NOT EXISTS notification (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(30)  NOT NULL COMMENT 'APPLY/STATUS_CHANGE/SYSTEM',
    title       VARCHAR(100) NOT NULL,
    content     VARCHAR(500) NOT NULL DEFAULT '',
    biz_id      BIGINT       NULL COMMENT '业务ID(如 applicationId)',
    read_flag   TINYINT      NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, read_flag)
) ENGINE = InnoDB COMMENT '站内通知表';

-- MQ 消费幂等表：msg_id 唯一索引，插入成功=首次消费
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    msg_id      VARCHAR(64) NOT NULL COMMENT 'RocketMQ 消息唯一ID',
    topic       VARCHAR(100) NOT NULL,
    biz_key     VARCHAR(100) NOT NULL DEFAULT '' COMMENT '业务幂等键',
    status      VARCHAR(20) NOT NULL DEFAULT 'CONSUMING' COMMENT 'CONSUMING/SUCCESS/FAILED',
    retry_count INT         NOT NULL DEFAULT 0,
    error_msg   VARCHAR(500) NOT NULL DEFAULT '',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT 'MQ 消费幂等表';

-- AI 会话审计表（真实记忆存 Redis）
CREATE TABLE IF NOT EXISTS ai_chat_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    question    VARCHAR(500) NOT NULL,
    answer      TEXT         NULL,
    tool_calls  VARCHAR(500) NOT NULL DEFAULT '' COMMENT '本次调用的工具名',
    token_cost  INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB COMMENT 'AI 对话审计表';

-- 限流配置表（运营后台可调，支持热更新）
CREATE TABLE IF NOT EXISTS rate_limit_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    limit_key    VARCHAR(64)  NOT NULL COMMENT '限流键,如 apply/ai_chat',
    dimension    VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER/IP/API',
    capacity     INT          NOT NULL DEFAULT 10 COMMENT '桶容量',
    rate         INT          NOT NULL DEFAULT 5 COMMENT '每秒补充令牌数',
    enabled      TINYINT      NOT NULL DEFAULT 1,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_dim (limit_key, dimension)
) ENGINE = InnoDB COMMENT '限流配置表';

INSERT INTO rate_limit_config (limit_key, dimension, capacity, rate) VALUES
  ('apply', 'USER', 5, 1),
  ('ai_chat', 'USER', 10, 2),
  ('login', 'IP', 20, 5)
ON DUPLICATE KEY UPDATE capacity = VALUES(capacity), rate = VALUES(rate);
