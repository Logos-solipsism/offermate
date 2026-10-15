package com.offermate.common.limiter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 令牌桶限流注解。
 * key 为限流业务键（与 rate_limit_config.limit_key 对应，可后台热调参）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 业务键，如 apply、ai_chat */
    String key();

    /** 桶容量：允许的突发上限 */
    int capacity() default 10;

    /** 每秒补充令牌数，数值上等于长期平均 QPS 上限 */
    int rate() default 5;

    /** 单次请求消耗令牌数 */
    int tokens() default 1;

    /** 限流维度 */
    Dimension dimension() default Dimension.USER;

    String message() default "操作过于频繁，请稍后再试";

    enum Dimension {
        /** 按登录用户维度（默认，防止单账号刷接口） */
        USER,
        /** 按客户端 IP 维度（防止未登录接口被刷） */
        IP,
        /** 按接口维度（保护下游/第三方资源） */
        API
    }
}
