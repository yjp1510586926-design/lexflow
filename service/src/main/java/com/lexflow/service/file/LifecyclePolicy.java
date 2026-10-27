package com.lexflow.service.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 保留天数为 0 时关闭导出到期或清理记录压缩；启用后由部署配置控制具体期限。 */
@Component
public class LifecyclePolicy {

    private final int exportRetentionDays;
    private final int cleanupCompactAfterDays;

    public LifecyclePolicy(
        @Value("${lexflow.lifecycle.export-retention-days:0}") int exportRetentionDays,
        @Value("${lexflow.lifecycle.cleanup-compact-after-days:0}") int cleanupCompactAfterDays
    ) {
        this.exportRetentionDays = days(exportRetentionDays);
        this.cleanupCompactAfterDays = days(cleanupCompactAfterDays);
    }

    private static int days(int value) {
        if (value < 0 || value > 3650) {
            throw new IllegalArgumentException("生命周期期限必须为0或1至3650天");
        }
        return value;
    }

    public int exportRetentionDays() {
        return exportRetentionDays;
    }

    public int cleanupCompactAfterDays() {
        return cleanupCompactAfterDays;
    }
}
