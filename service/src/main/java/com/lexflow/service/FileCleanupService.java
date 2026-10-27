package com.lexflow.service;

import com.lexflow.dao.FileCleanupMapper;
import com.lexflow.service.file.PrivateObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileCleanupService {

    private final FileCleanupMapper jobs;
    private final PrivateObjectStorage storage;

    public FileCleanupService(FileCleanupMapper jobs, PrivateObjectStorage storage) {
        this.jobs = jobs;
        this.storage = storage;
    }

    /** 锁定一条到期任务直至删除结束；进程中断会回滚，重复删除不存在的对象仍可安全重试。 */
    @Transactional
    public boolean processOne() {
        var job = jobs.lockNext();
        if (job == null || job.isEmpty()) {
            return false;
        }
        long id = ((Number) job.get("id")).longValue();
        // 超时上传先转 FAILED，阻止迟到的上传完成操作将已清理对象重新发布。
        if (job.containsKey("tenant_id")) {
            jobs.expireUpload(
                ((Number) job.get("tenant_id")).longValue(),
                ((Number) job.get("file_id")).longValue()
            );
        }
        try {
            storage.remove((String) job.get("object_key"));
        } catch (Exception e) {
            // 保留任务并指数退避，错误只记录类型，不暴露对象存储返回的敏感详情。
            int attempts = ((Number) job.get("attempts")).intValue();
            int delay = (int) Math.min(3600L, 10L * (1L << Math.min(attempts, 9)));
            jobs.retry(id, delay, e.getClass().getSimpleName());
            return true;
        }
        jobs.done(id);
        return true;
    }
}
