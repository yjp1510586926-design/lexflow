package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.FileCleanupMapper;
import com.lexflow.dao.FileObjectMapper;
import com.lexflow.dao.FileVersionMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.FileObjectModel;
import com.lexflow.dao.model.FileVersionModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.file.UploadInspector;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用独立短事务包围对象上传；调用方须在数据库事务外执行对象存储 I/O。 */
@Service
public class UploadMetadataService {

    private final FileObjectMapper files;
    private final FileVersionMapper versions;
    private final FileCleanupMapper cleanup;
    private final AuditLogMapper audit;
    private final CaseService cases;

    public UploadMetadataService(
        FileObjectMapper files,
        FileVersionMapper versions,
        FileCleanupMapper cleanup,
        AuditLogMapper audit,
        CaseService cases
    ) {
        this.files = files;
        this.versions = versions;
        this.cleanup = cleanup;
        this.audit = audit;
        this.cases = cases;
    }

    /** 已登记但尚未发布的上传记录；key 是预先分配的私有对象路径，失败补偿也沿用此路径。 */
    public record Pending(long fileId, String key) {}

    /** 上传字节前登记 UPLOADING 元数据与延后清理任务，进程中断后仍有可追踪的回收入口。 */
    @Transactional
    public Pending begin(Identity identity, long caseId, UploadInspector.Inspected inspectedFile) {
        cases.authorize(identity, caseId, Action.UPLOAD, "file:upload", true);
        var file = FileObjectModel.builder()
            .tenantId(identity.tenantId())
            .caseId(caseId)
            .displayName(inspectedFile.displayName())
            .status("UPLOADING")
            .createdBy(identity.userId())
            .build();
        files.insert(file);
        String key =
            "tenants/" + identity.tenantId() + "/cases/" + caseId + "/" + file.getId() + "/v1";
        versions.insert(
            FileVersionModel.builder()
                .tenantId(identity.tenantId())
                .fileId(file.getId())
                .versionNo(1)
                .objectKey(key)
                .sha256(inspectedFile.sha256())
                .sizeBytes(inspectedFile.size())
                .mimeType(inspectedFile.contentType())
                .build()
        );
        // 预先登记并延后清理，上传进程中断时也能回收对象；成功完成时删除这条兜底任务。
        cleanup.enqueueVersions(identity.tenantId(), file.getId());
        cleanup.deferUpload(identity.tenantId(), file.getId());
        return new Pending(file.getId(), key);
    }

    /** 上传期间案件可能归档或权限改变；再次授权后发布 UPLOADING 记录，并撤销兜底清理任务。 */
    @Transactional
    public void complete(Identity identity, long caseId, long fileId) {
        cases.authorize(identity, caseId, Action.UPLOAD, "file:upload", true);
        int changed = files.update(
            null,
            new LambdaUpdateWrapper<FileObjectModel>()
                .eq(FileObjectModel::getTenantId, identity.tenantId())
                .eq(FileObjectModel::getId, fileId)
                .eq(FileObjectModel::getStatus, "UPLOADING")
                .set(FileObjectModel::getStatus, "AVAILABLE")
        );
        if (changed != 1) {
            throw new IllegalStateException("上传已结束或失效");
        }
        cleanup.cancelUpload(identity.tenantId(), fileId);
        audit.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action("file:upload")
                .objectType("file")
                .objectId(Long.toString(fileId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }

    /** 失败补偿重新启用按租户登记的清理任务，状态回写仅作用于仍在 UPLOADING 的记录。 */
    @Transactional
    public void fail(Identity identity, long fileId) {
        files.update(
            null,
            new LambdaUpdateWrapper<FileObjectModel>()
                .eq(FileObjectModel::getTenantId, identity.tenantId())
                .eq(FileObjectModel::getId, fileId)
                .eq(FileObjectModel::getStatus, "UPLOADING")
                .set(FileObjectModel::getStatus, "FAILED")
        );
        cleanup.enqueueVersions(identity.tenantId(), fileId);
        cleanup.rearm(identity.tenantId(), fileId);
        audit.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action("file:upload")
                .objectType("file")
                .objectId(Long.toString(fileId))
                .result("FAILED")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
