package com.lexflow.website.core.storage;

import com.lexflow.website.biz.DocumentExportBiz;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 调度租约恢复、结果到期和历史产物清理；业务层复核状态后才撤销指针或删除对象。 */
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentExportMaintenance {

    private final DocumentExportBiz exports;

    public DocumentExportMaintenance(DocumentExportBiz exports) {
        this.exports = exports;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 15000)
    public void maintain() {
        exports.maintain();
    }
}
