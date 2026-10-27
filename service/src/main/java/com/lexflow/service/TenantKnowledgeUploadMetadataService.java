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
import com.lexflow.service.permission.AccessDenied;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立公共知识来源的上传元数据；短事务与私有对象 I/O 分离，沿用上传失败清理机制。 */
@Service
public class TenantKnowledgeUploadMetadataService {

    private final FileObjectMapper files;
    private final FileVersionMapper versions;
    private final FileCleanupMapper cleanup;
    private final AuditLogMapper audit;
    private final KnowledgeAccessService access;

    public TenantKnowledgeUploadMetadataService(
        FileObjectMapper files,
        FileVersionMapper versions,
        FileCleanupMapper cleanup,
        AuditLogMapper audit,
        KnowledgeAccessService access
    ) {
        this.files = files;
        this.versions = versions;
        this.cleanup = cleanup;
        this.audit = audit;
        this.access = access;
    }

    public record Pending(long fileId, String key) {}

    @Transactional
    public Pending begin(Identity actor, long baseId, UploadInspector.Inspected inspected) {
        requireTenant(actor, baseId);
        var file = FileObjectModel.builder()
            .tenantId(actor.tenantId())
            .caseId(null)
            .scope("TENANT")
            .knowledgeBaseId(baseId)
            .displayName(inspected.displayName())
            .status("UPLOADING")
            .createdBy(actor.userId())
            .build();
        files.insert(file);
        String key =
            "tenants/" + actor.tenantId() + "/knowledge/" + baseId + "/" + file.getId() + "/v1";
        versions.insert(
            FileVersionModel.builder()
                .tenantId(actor.tenantId())
                .fileId(file.getId())
                .versionNo(1)
                .objectKey(key)
                .sha256(inspected.sha256())
                .sizeBytes(inspected.size())
                .mimeType(inspected.contentType())
                .build()
        );
        // 上传开始即登记清理兜底，成功后取消；进程中断后仍可按超时回收。
        cleanup.enqueueVersions(actor.tenantId(), file.getId());
        cleanup.deferUpload(actor.tenantId(), file.getId());
        return new Pending(file.getId(), key);
    }

    @Transactional
    public void complete(Identity actor, long baseId, long fileId) {
        requireTenant(actor, baseId);
        int updated = files.update(
            null,
            new LambdaUpdateWrapper<FileObjectModel>()
                .eq(FileObjectModel::getTenantId, actor.tenantId())
                .eq(FileObjectModel::getId, fileId)
                .eq(FileObjectModel::getScope, "TENANT")
                .eq(FileObjectModel::getKnowledgeBaseId, baseId)
                .isNull(FileObjectModel::getCaseId)
                .eq(FileObjectModel::getStatus, "UPLOADING")
                .set(FileObjectModel::getStatus, "AVAILABLE")
        );
        if (updated != 1) {
            throw new IllegalStateException("上传已结束或失效");
        }
        cleanup.cancelUpload(actor.tenantId(), fileId);
        audit(actor, "knowledge:file:upload", fileId, "SUCCESS");
    }

    @Transactional
    public void fail(Identity actor, long baseId, long fileId) {
        // 上传者失去权限后仍需补偿，但更新条件始终限定原租户、知识库和上传中的对象。
        int updated = files.update(
            null,
            new LambdaUpdateWrapper<FileObjectModel>()
                .eq(FileObjectModel::getTenantId, actor.tenantId())
                .eq(FileObjectModel::getId, fileId)
                .eq(FileObjectModel::getScope, "TENANT")
                .eq(FileObjectModel::getKnowledgeBaseId, baseId)
                .isNull(FileObjectModel::getCaseId)
                .eq(FileObjectModel::getStatus, "UPLOADING")
                .set(FileObjectModel::getStatus, "FAILED")
        );
        if (updated == 1) {
            cleanup.enqueueVersions(actor.tenantId(), fileId);
            cleanup.rearm(actor.tenantId(), fileId);
            audit(actor, "knowledge:file:upload", fileId, "FAILED");
        }
    }

    private void requireTenant(Identity actor, long baseId) {
        var base = access.manage(actor, baseId);
        if (!"TENANT".equals(base.scope()) || base.caseId() != null) {
            throw new AccessDenied();
        }
    }

    private void audit(Identity actor, String action, long fileId, String result) {
        audit.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action(action)
                .objectType("knowledge_file")
                .objectId(Long.toString(fileId))
                .result(result)
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
