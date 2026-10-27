package com.lexflow.service;

import com.lexflow.dao.CleanupTombstoneRepository;
import com.lexflow.service.file.LifecyclePolicy;
import com.lexflow.service.file.PrivateObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 已完成清理记录可压缩为永久墓碑；周期复查可回收迟到写入的同一对象键。 */
@Service
public class CleanupLifecycleService {

    private final LifecyclePolicy policy;
    private final CleanupTombstoneRepository tombstones;
    private final PrivateObjectStorage storage;

    public CleanupLifecycleService(
        LifecyclePolicy policy,
        CleanupTombstoneRepository tombstones,
        PrivateObjectStorage storage
    ) {
        this.policy = policy;
        this.tombstones = tombstones;
        this.storage = storage;
    }

    @Transactional
    public boolean compactFile() {
        if (policy.cleanupCompactAfterDays() == 0) {
            return false;
        }
        var entry = tombstones.completedFile(policy.cleanupCompactAfterDays());
        if (entry == null) {
            return false;
        }
        tombstones.compactFile(entry);
        return true;
    }

    @Transactional
    public boolean compactExport() {
        if (policy.cleanupCompactAfterDays() == 0) {
            return false;
        }
        var entry = tombstones.completedExport(policy.cleanupCompactAfterDays());
        if (entry == null) {
            return false;
        }
        tombstones.compactExport(entry);
        return true;
    }

    /** 删除期间持有归属记录锁，防止并发发布使该对象重新成为有效结果。 */
    @Transactional
    public boolean review(boolean file) {
        var entry = file ? tombstones.dueFile() : tombstones.dueExport();
        if (entry == null) {
            return false;
        }
        String error = null;
        try {
            storage.remove(entry.key());
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
            if (error.length() > 100) {
                error = error.substring(0, 100);
            }
        }
        tombstones.reviewed(entry, file, error);
        return true;
    }
}
