package com.offermate.common.trace;

import org.slf4j.MDC;

/** 在消费端把消息里带的 traceId 放回日志上下文，让消费日志和产生它的那次请求串起来。 */
public final class TraceUtil {

    private TraceUtil() {
    }

    public static void start(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TraceIdFilter.TRACE_ID, traceId);
        }
    }

    public static void clear() {
        MDC.remove(TraceIdFilter.TRACE_ID);
    }
}
