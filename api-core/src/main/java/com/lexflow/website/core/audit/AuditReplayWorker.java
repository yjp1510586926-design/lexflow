package com.lexflow.website.core.audit;

import com.lexflow.service.AuditReplayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计离线缓冲回放定时任务。
 * <p>
 * 当数据库不可用时，审计事件写入内存离线缓冲（{@link com.lexflow.service.audit.AuditOfflineBuffer}）。
 * 本任务每 15 秒（可通过 {@code lexflow.audit.replay-delay-ms} 调整）尝试将缓冲中的事件
 * 持久化到数据库，确保短暂故障期间的审计记录不丢失。
 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AuditReplayWorker {

    private final AuditReplayService replay;

    public AuditReplayWorker(AuditReplayService replay) {
        this.replay = replay;
    }

    @Scheduled(fixedDelayString = "${lexflow.audit.replay-delay-ms:15000}", initialDelay = 15000)
    public void replay() {
        replay.replay();
    }
}
