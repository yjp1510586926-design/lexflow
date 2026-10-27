package com.lexflow.service;

import com.lexflow.service.audit.AuditOfflineBuffer;
import com.lexflow.service.audit.AuditOperationalMetrics;
import com.lexflow.service.audit.RequestAuditDatabaseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 数据库事务成功后才移除离线记录，重复重放按事件身份及内容摘要去重。 */
@Service
public class AuditReplayService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(
        AuditReplayService.class
    );
    private final RequestAuditDatabaseWriter database;
    private final AuditOfflineBuffer buffer;
    private final AuditOperationalMetrics metrics;
    private final int batchSize;

    public AuditReplayService(
        RequestAuditDatabaseWriter database,
        AuditOfflineBuffer buffer,
        AuditOperationalMetrics metrics,
        @Value("${lexflow.audit.replay-batch-size:100}") int batchSize
    ) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Invalid audit replay batch");
        }
        this.database = database;
        this.buffer = buffer;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    public synchronized int replay() {
        int replayed = 0;
        try {
            for (var entry : buffer.batch(batchSize)) {
                RequestAuditDatabaseWriter.Outcome result;
                try {
                    result = database.write(entry.event());
                    metrics.databaseAvailable(true);
                } catch (RuntimeException unavailable) {
                    metrics.databaseAvailable(false);
                    throw unavailable;
                }
                buffer.acknowledge(entry);
                replayed++;
                metrics.increment(AuditOperationalMetrics.Signal.REPLAYED);
                if (result == RequestAuditDatabaseWriter.Outcome.DUPLICATE) {
                    metrics.increment(AuditOperationalMetrics.Signal.DUPLICATE);
                }
            }
        } catch (RuntimeException unavailable) {
            metrics.increment(AuditOperationalMetrics.Signal.REPLAY_FAILURE);
            LOG.error("Request audit replay unavailable; pending records retained");
        }
        return replayed;
    }
}
