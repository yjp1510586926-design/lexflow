package com.lexflow.framework.context;

import java.util.UUID;
import org.slf4j.MDC;

/** 请求追踪标识由服务端生成，并同时写入线程上下文和日志 MDC；不会自动传递到异步线程。 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {}

    public static String currentOrNew() {
        // 非请求线程只返回临时标识，不隐式建立需要清理的线程上下文。
        String current = CURRENT.get();
        return current != null ? current : UUID.randomUUID().toString();
    }

    /** 调用方须通过 try-with-resources 关闭作用域，避免容器线程复用时遗留追踪标识。 */
    public static Scope startRequest() {
        String previous = CURRENT.get();
        String previousMdc = MDC.get("traceId");
        String trace = UUID.randomUUID().toString();
        CURRENT.set(trace);
        MDC.put("traceId", trace);
        return new Scope(trace, previous, previousMdc);
    }

    public static final class Scope implements AutoCloseable {

        private final String traceId, previous, previousMdc;
        private boolean closed;

        private Scope(String traceId, String previous, String previousMdc) {
            this.traceId = traceId;
            this.previous = previous;
            this.previousMdc = previousMdc;
        }

        public String traceId() {
            return traceId;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // 恢复进入作用域前的值，兼容嵌套调用并避免线程复用时串入上一请求。
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousMdc == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previousMdc);
            }
        }
    }
}
