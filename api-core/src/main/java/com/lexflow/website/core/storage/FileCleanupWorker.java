package com.lexflow.website.core.storage;

import com.lexflow.service.CleanupLifecycleService;
import com.lexflow.service.FileCleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 分批清理私有对象并复查永久墓碑，确保记录压缩后仍能回收同一对象键的迟到写入。 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class FileCleanupWorker {

    private final FileCleanupService cleanup;
    private final CleanupLifecycleService lifecycle;

    public FileCleanupWorker(FileCleanupService cleanup, CleanupLifecycleService lifecycle) {
        this.cleanup = cleanup;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 15000)
    public void clean() {
        for (int i = 0; i < 10 && cleanup.processOne(); i++) {
            /* 单轮最多处理 10 个作业，避免持续积压长期占用调度线程。 */
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void compact() {
        for (int i = 0; i < 10 && lifecycle.compactFile(); i++) {
            /* 仅压缩已完成且满足保留期限的文件清理记录。 */
        }
        for (int i = 0; i < 10 && lifecycle.compactExport(); i++) {
            /* 当前发布结果排除在导出产物压缩范围外。 */
        }
        for (int i = 0; i < 10 && lifecycle.review(true); i++) {
            /* 文件墓碑复查准确对象键，回收迟到的同键写入。 */
        }
        for (int i = 0; i < 10 && lifecycle.review(false); i++) {
            /* 导出墓碑复查前再确认对象未被任务重新发布。 */
        }
    }
}
