package com.lexflow.service.audit;

import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Service;

/** 仅使用固定的全局运维标签，避免以用户、租户、追踪标识、URL 或异常文本泄露数据并增加指标基数。 */
@Service
public class AuditOperationalMetrics {

    public enum Signal {
        COMMITTED,
        BUFFERED,
        UNAVAILABLE,
        REPLAYED,
        DUPLICATE,
        REPLAY_FAILURE,
        INTEGRITY_FAILURE,
        BUFFER_FULL,
    }

    private final EnumMap<Signal, LongAdder> counters = new EnumMap<>(Signal.class);
    // 可用性表示最近一次持久化或回放尝试的结果，并非对下一次写入的保证。
    private volatile boolean databaseAvailable;
    private volatile boolean bufferAvailable;

    public AuditOperationalMetrics() {
        for (var signal : Signal.values()) {
            counters.put(signal, new LongAdder());
        }
    }

    public void increment(Signal signal) {
        counters.get(signal).increment();
    }

    public long count(Signal signal) {
        return counters.get(signal).sum();
    }

    public void databaseAvailable(boolean available) {
        databaseAvailable = available;
    }

    public void bufferAvailable(boolean available) {
        bufferAvailable = available;
    }

    public String render(AuditOfflineBuffer.Snapshot snapshot) {
        var out = new StringBuilder(
            "# HELP lexflow_audit_events_total Request audit persistence outcomes since process start.\n# TYPE lexflow_audit_events_total counter\n"
        );
        for (var signal : Signal.values()) {
            out.append("lexflow_audit_events_total{outcome=\"")
                .append(signal.name().toLowerCase(Locale.ROOT))
                .append("\"} ")
                .append(count(signal))
                .append('\n');
        }
        gauge(out, "lexflow_audit_database_available", databaseAvailable ? 1 : 0);
        gauge(
            out,
            "lexflow_audit_buffer_available",
            bufferAvailable && snapshot.available() ? 1 : 0
        );
        gauge(out, "lexflow_audit_buffer_records", snapshot.records());
        gauge(out, "lexflow_audit_buffer_bytes", snapshot.bytes());
        gauge(out, "lexflow_audit_buffer_max_records", snapshot.maxRecords());
        gauge(out, "lexflow_audit_buffer_max_bytes", snapshot.maxBytes());
        gauge(out, "lexflow_audit_buffer_oldest_age_seconds", snapshot.oldestAgeSeconds());
        return out.toString();
    }

    public static void gauge(StringBuilder out, String name, long value) {
        out.append("# TYPE ")
            .append(name)
            .append(" gauge\n")
            .append(name)
            .append(' ')
            .append(value)
            .append('\n');
    }
}
