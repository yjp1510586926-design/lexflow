package com.lexflow.service.audit;

import com.lexflow.service.identity.Identity;
import org.springframework.stereotype.Service;

/** 登记请求层结果；业务成功审计由各业务服务与业务变更同事务保存。 */
@Service
public class RequestAuditWriter {

    private final RequestAuditDatabaseWriter database;
    private final AuditOfflineBuffer buffer;
    private final AuditOperationalMetrics metrics;

    public RequestAuditWriter(
        RequestAuditDatabaseWriter database,
        AuditOfflineBuffer buffer,
        AuditOperationalMetrics metrics
    ) {
        this.database = database;
        this.buffer = buffer;
        this.metrics = metrics;
    }

    public enum Outcome {
        COMMITTED,
        BUFFERED,
    }

    public Outcome record(
        Identity actor,
        String action,
        int status,
        String method,
        String route,
        String objectId,
        String traceId
    ) {
        RequestAuditEvent event;
        try {
            event = RequestAuditEvent.create(
                actor,
                action,
                status,
                method,
                route,
                objectId,
                traceId
            );
        } catch (RuntimeException invalid) {
            metrics.increment(AuditOperationalMetrics.Signal.UNAVAILABLE);
            throw invalid;
        }
        try {
            database.write(event);
            metrics.databaseAvailable(true);
            metrics.increment(AuditOperationalMetrics.Signal.COMMITTED);
            return Outcome.COMMITTED;
        } catch (RuntimeException unavailable) {
            metrics.databaseAvailable(false);
            // 数据库调用连同事务提交失败时，使用同一事件身份落本地缓冲，供之后幂等重放。
            try {
                buffer.append(event);
                metrics.increment(AuditOperationalMetrics.Signal.BUFFERED);
                return Outcome.BUFFERED;
            } catch (RuntimeException durabilityUnavailable) {
                metrics.increment(AuditOperationalMetrics.Signal.UNAVAILABLE);
                throw new IllegalStateException("Request audit durability unavailable");
            }
        }
    }
}
