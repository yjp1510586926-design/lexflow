package com.lexflow.website.biz;

import com.lexflow.dao.DocumentExportRepository;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.DocumentExportService;
import com.lexflow.service.DocumentExportService.Claim;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/** 对象存储 I/O 在 SQL 事务外执行；结果发布仍须验证执行租约和当前权限。 */
@Service
public class DocumentExportBiz {

    private final DocumentExportService exports;
    private final DocumentExportRepository jobs;
    private final PrivateObjectStorage storage;

    public DocumentExportBiz(
        DocumentExportService exports,
        DocumentExportRepository jobs,
        PrivateObjectStorage storage
    ) {
        this.exports = exports;
        this.jobs = jobs;
        this.storage = storage;
    }

    /** 按任务轮次和状态版本领取执行资格；对象上传完成后，仍须由服务层确认租约再发布。 */
    public void process(long tenant, long task, int attempt, int version) {
        Claim claim;
        try {
            claim = exports.begin(tenant, task, attempt, version);
        } catch (AccessDenied | BusinessException | IllegalArgumentException e) {
            exports.failQueued(tenant, task, attempt, version);
            return;
        }
        if (claim == null) {
            return;
        }
        Path file = null;
        try {
            try {
                file = Files.createTempFile("lexflow-export-", ".txt");
                Files.write(file, claim.bytes());
                storage.put(claim.objectKey(), file, "text/plain;charset=UTF-8");
            } catch (Exception e) {
                exports.fail(claim, true);
                return;
            }
            try {
                exports.complete(claim);
            } catch (AccessDenied | BusinessException | IllegalArgumentException e) {
                exports.fail(claim, false);
            }
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    /* 此处仅清理本机临时文件，导出对象的发布与清理由任务记录控制。 */
                }
            }
            // SQL 提交结果不确定时不能直接删除上传对象，已登记的清理任务会先检查对象是否已发布。
        }
    }

    /** 校验对象大小、摘要和读取后的权限，全部通过后才向调用方交付字节。 */
    public byte[] download(Identity actor, long task) {
        var metadata = exports.download(actor, task);
        byte[] bytes;
        try (var in = storage.open(metadata.key())) {
            bytes = in.readNBytes(DocumentExportService.MAX_BYTES + 1);
        } catch (Exception e) {
            throw new BusinessException(503, "导出文件暂时无法读取，请稍后再试");
        }
        if (
            bytes.length != metadata.size() ||
            !DocumentExportService.sha256(bytes).equals(metadata.sha256())
        ) {
            throw BusinessException.conflict("导出文件校验失败，请重新导出");
        }
        // 读取期间任务可能到期或权限变化；确认仍可下载并记录审计后才能把字节交给调用方。
        exports.auditDownload(actor, task, metadata);
        return bytes;
    }

    /** 收回过期租约、过期结果；对象清理须先预约，再记录成功或失败以便后续重试。 */
    public void maintain() {
        for (var ref : jobs.expired()) {
            exports.recoverExpired(ref.tenant(), ref.task());
        }
        for (var ref : jobs.resultsDue()) {
            exports.expireResult(ref.tenant(), ref.task());
        }
        for (var artifact : jobs.cleanupCandidates()) {
            if (!exports.reserveCleanup(artifact)) {
                continue;
            }
            String error = null;
            try {
                storage.remove(artifact.objectKey());
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
                if (error.length() > 100) {
                    error = error.substring(0, 100);
                }
            }
            jobs.cleaned(artifact, error);
        }
    }
}
