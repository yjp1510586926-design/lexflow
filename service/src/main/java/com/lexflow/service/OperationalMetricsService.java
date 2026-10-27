package com.lexflow.service;

import com.lexflow.service.audit.AuditOperationalMetrics;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Aggregate operational state only. Labels are code constants, never database identifiers or error text. */
@Service
public class OperationalMetricsService {

    private final JdbcTemplate db;
    private static final Set<String> GROUPS = Set.of("task", "outbox", "file_cleanup", "delivery");
    private static final Set<String> STATES = Set.of(
        "QUEUED",
        "RUNNING",
        "WAITING_HUMAN",
        "SUCCEEDED",
        "FAILED",
        "CANCEL_REQUESTED",
        "CANCELLED",
        "PENDING",
        "SENT",
        "DONE",
        "DEAD",
        "RESOLVED"
    );

    private record Count(String group, String state, long count, long oldestSeconds) {}

    public OperationalMetricsService(JdbcTemplate db) {
        this.db = db;
    }

    public String render() {
        var output = new StringBuilder();
        List<Count> counts;
        try {
            counts = db.query(
                connection -> {
                    var statement = connection.prepareStatement(
                        """
                        SELECT 'task' category,status state,COUNT(*) total,
                          COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP())),0) oldest
                        FROM async_task GROUP BY status
                        UNION ALL SELECT 'outbox',status,COUNT(*),
                          COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP())),0)
                        FROM outbox_event GROUP BY status
                        UNION ALL SELECT 'file_cleanup',status,COUNT(*),
                          COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP())),0)
                        FROM file_cleanup_job GROUP BY status
                        UNION ALL SELECT 'delivery',delivery_status,COUNT(*),
                          COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(dead_lettered_at),UTC_TIMESTAMP())),0)
                        FROM task_delivery_recovery GROUP BY delivery_status
                        """
                    );
                    statement.setQueryTimeout(3);
                    statement.setMaxRows(32);
                    return statement;
                },
                (row, index) ->
                    new Count(
                        row.getString("category"),
                        row.getString("state"),
                        row.getLong("total"),
                        row.getLong("oldest")
                    )
            );
            if (
                counts
                    .stream()
                    .anyMatch(
                        count ->
                            !GROUPS.contains(count.group()) ||
                            !STATES.contains(count.state()) ||
                            count.count() < 0 ||
                            count.oldestSeconds() < 0
                    )
            ) {
                throw new IllegalStateException("Invalid operational aggregate");
            }
            AuditOperationalMetrics.gauge(output, "lexflow_operational_database_available", 1);
            output.append(
                "# TYPE lexflow_operational_records gauge\n# TYPE lexflow_operational_oldest_age_seconds gauge\n"
            );
            for (var group : List.of("task", "outbox", "file_cleanup", "delivery")) {
                var states = switch (group) {
                    case "task" -> List.of(
                        "QUEUED",
                        "RUNNING",
                        "WAITING_HUMAN",
                        "SUCCEEDED",
                        "FAILED",
                        "CANCEL_REQUESTED",
                        "CANCELLED"
                    );
                    case "outbox" -> List.of("PENDING", "SENT", "FAILED");
                    case "file_cleanup" -> List.of("PENDING", "DONE");
                    default -> List.of("DEAD", "RESOLVED");
                };
                for (var state : states) {
                    var count = counts
                        .stream()
                        .filter(item -> item.group().equals(group) && item.state().equals(state))
                        .findFirst()
                        .orElse(new Count(group, state, 0, 0));
                    output
                        .append("lexflow_operational_records{category=\"")
                        .append(group)
                        .append("\",state=\"")
                        .append(state.toLowerCase(Locale.ROOT))
                        .append("\"} ")
                        .append(count.count())
                        .append('\n');
                    if (List.of("QUEUED", "RUNNING", "PENDING", "DEAD").contains(state)) {
                        output
                            .append("lexflow_operational_oldest_age_seconds{category=\"")
                            .append(group)
                            .append("\",state=\"")
                            .append(state.toLowerCase(Locale.ROOT))
                            .append("\"} ")
                            .append(count.oldestSeconds())
                            .append('\n');
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // Omit uncertain counts instead of emitting fabricated zeroes or private exception text.
            AuditOperationalMetrics.gauge(output, "lexflow_operational_database_available", 0);
        }
        var runtime = Runtime.getRuntime();
        AuditOperationalMetrics.gauge(
            output,
            "lexflow_jvm_heap_used_bytes",
            runtime.totalMemory() - runtime.freeMemory()
        );
        AuditOperationalMetrics.gauge(output, "lexflow_jvm_heap_max_bytes", runtime.maxMemory());
        return output.toString();
    }
}
