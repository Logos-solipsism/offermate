package com.offermate.common.trace;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求生成一个 traceId，放进日志上下文（MDC）和响应头。
 *
 * 为什么需要它：一次"投递"会跨三个地方——HTTP 接口 → 消息队列 → 消费端。
 * 出问题时如果没有 traceId，你只能靠时间点去"猜"哪些日志是同一次请求的。
 * 有了它，接口日志、消息、消费日志都能用同一个 ID 串起来（响应头也会带回给前端）。
 */
@Component
@Order(1)
public class TraceIdFilter implements Filter {

    /** 日志里的键名，也是响应头名字 */
    public static final String TRACE_ID = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 上游（网关/前端）如果已经带了 traceId 就沿用，保证跨服务也能串起来
        String traceId = null;
        if (request instanceof jakarta.servlet.http.HttpServletRequest httpRequest) {
            traceId = httpRequest.getHeader(HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader(HEADER, traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被复用，必须清理，否则会串到下一个请求上
            MDC.remove(TRACE_ID);
        }
    }
}
