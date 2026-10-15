package com.offermate.common.limiter;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 限流配置表：运营改阈值不用改代码、不用重启，注解上的值只是兜底默认值。 */
@Data
@TableName("rate_limit_config")
public class RateLimitConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 与 @RateLimit(key = ...) 对应，如 apply / ai_chat / login */
    private String limitKey;

    /** USER / IP / API */
    private String dimension;

    /** 桶容量（允许的突发上限） */
    private Integer capacity;

    /** 每秒补充令牌数（长期平均 QPS） */
    private Integer rate;

    private Integer enabled;

    private LocalDateTime updateTime;
}
