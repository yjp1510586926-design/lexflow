package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.FileCleanupMapper;
import com.lexflow.dao.FileObjectMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.FileObjectModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.file.UploadInspector;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 公共知识材料独立绑定律所知识库，每次操作同时校验租户、范围及知识库归属。 */
@Service
public class TenantKnowledgeFileService {

    private final FileObjectMapper files;
    private final FileCleanupMapper cleanup;
    private final AuditLogMapper audits;
    private final KnowledgeAccessService access;
    private final TenantKnowledgeUploadMetadataService uploads;
    private final UploadInspector inspector;
    private final PrivateObjectStorage storage;
    private final TaskService tasks;

    public TenantKnowledgeFileService(
        FileObjectMapper files,
        FileCleanupMapper cleanup,
        AuditLogMapper audits,
        KnowledgeAccessService access,
        TenantKnowledgeUploadMetadataService uploads,
        UploadInspector inspector,
        PrivateObjectStorage storage,
        TaskService tasks
    ) {
        this.files = files;
        this.cleanup = cleanup;
        this.audits = audits;
        this.access = access;
        this.uploads = uploads;
        this.inspector = inspector;
        this.storage = storage;
        this.tasks = tasks;
    }

    public record FileView(
        String id,
        String caseId,
        String knowledgeBaseId,
        String displayName,
        String status,
        String mimeType,
        long sizeBytes,
        String sha256,
        int versionNo,
        String versionId,
        String createdBy,
        String createdAt,
        String parseTaskId,
        String parseStatus
    ) {}

    public record Download(byte[] bytes, String mimeType, String displayName) {}

    public List<FileView> list(Identity actor, long baseId) {
        requireTenant(actor, baseId, false);
        return files
            .listTenantKnowledgeFiles(actor.tenantId(), baseId)
            .stream()
            .map(TenantKnowledgeFileService::toFileView)
            .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FileView upload(
        Identity actor,
        long baseId,
        Path file,
        String originalName,
        String contentType
    ) throws Exception {
        requireTenant(actor, baseId, true);
        var inspected = inspector.inspect(file, originalName, contentType);
        // 公共知识入库依赖文字层解析，当前仅接受 TXT、PDF 和 DOCX。
        if (
            !Set.of(
                "text/plain",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ).contains(inspected.contentType())
        ) {
            throw new IllegalArgumentException("公共知识材料请使用 TXT、含文字层 PDF 或 DOCX");
        }
        var pending = uploads.begin(actor, baseId, inspected);
        try {
            storage.put(pending.key(), file, inspected.contentType());
            uploads.complete(actor, baseId, pending.fileId());
        } catch (Exception failure) {
            try {
                uploads.fail(actor, baseId, pending.fileId());
            } catch (RuntimeException compensation) {
                failure.addSuppressed(compensation);
            }
            throw new IllegalStateException("知识材料上传失败，请稍后重试", failure);
        }
        return toFileView(requireFile(actor, baseId, pending.fileId(), false));
    }

    @Transactional
    public TaskService.TaskView parse(
        Identity actor,
        long baseId,
        long fileId,
        long versionId,
        String idempotencyKey
    ) {
        var file = requireFile(actor, baseId, fileId, true);
        if (
            !"AVAILABLE".equals(file.get("status")) ||
            ((Number) file.get("version_id")).longValue() != versionId
        ) {
            throw new AccessDenied();
        }
        return tasks.submitKnowledgeParse(actor, baseId, versionId, idempotencyKey);
    }

    public Download download(Identity actor, long baseId, long fileId) throws Exception {
        var source = requireFile(actor, baseId, fileId, false);
        if (!"AVAILABLE".equals(source.get("status"))) {
            throw new AccessDenied();
        }
        byte[] bytes;
        try (var stream = storage.open((String) source.get("object_key"))) {
            bytes = stream.readNBytes((int) UploadInspector.MAX_BYTES + 1);
        }
        // 网络读取期间来源可能被删除或权限改变，交付字节前重查权限、版本和内容摘要。
        var current = requireFile(actor, baseId, fileId, false);
        if (
            !"AVAILABLE".equals(current.get("status")) ||
            !Objects.equals(source.get("version_id"), current.get("version_id"))
        ) {
            throw new AccessDenied();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (
            bytes.length != ((Number) source.get("size_bytes")).longValue() ||
            !hash.equals(source.get("sha256"))
        ) {
            throw new IllegalStateException("知识材料完整性校验失败");
        }
        audit(actor, "knowledge:file:download", fileId);
        return new Download(
            bytes,
            (String) source.get("mime_type"),
            (String) source.get("display_name")
        );
    }

    @Transactional
    public void delete(Identity actor, long baseId, long fileId) {
        requireFile(actor, baseId, fileId, true);
        int updated = files.update(
            null,
            new LambdaUpdateWrapper<FileObjectModel>()
                .eq(FileObjectModel::getTenantId, actor.tenantId())
                .eq(FileObjectModel::getId, fileId)
                .eq(FileObjectModel::getScope, "TENANT")
                .eq(FileObjectModel::getKnowledgeBaseId, baseId)
                .isNull(FileObjectModel::getCaseId)
                .ne(FileObjectModel::getStatus, "DELETED")
                .set(FileObjectModel::getStatus, "DELETED")
                .setSql("deleted_at=CURRENT_TIMESTAMP(6)")
        );
        if (updated != 1) {
            throw new AccessDenied();
        }
        cleanup.enqueueVersions(actor.tenantId(), fileId);
        audit(actor, "knowledge:file:delete", fileId);
    }

    private Map<String, Object> requireFile(
        Identity actor,
        long baseId,
        long fileId,
        boolean writing
    ) {
        requireTenant(actor, baseId, writing);
        var file = files.tenantKnowledgeFile(actor.tenantId(), baseId, fileId);
        if (file == null || file.isEmpty()) {
            throw new AccessDenied();
        }
        return file;
    }

    private void requireTenant(Identity actor, long baseId, boolean writing) {
        var base = writing ? access.manage(actor, baseId) : access.read(actor, baseId);
        if (!"TENANT".equals(base.scope()) || base.caseId() != null) {
            throw new AccessDenied();
        }
    }

    private static String string(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : row.get(key).toString();
    }

    private static FileView toFileView(Map<String, Object> row) {
        return new FileView(
            string(row, "id"),
            null,
            string(row, "knowledge_base_id"),
            string(row, "display_name"),
            string(row, "status"),
            string(row, "mime_type"),
            row.get("size_bytes") == null ? 0 : ((Number) row.get("size_bytes")).longValue(),
            string(row, "sha256"),
            row.get("version_no") == null ? 0 : ((Number) row.get("version_no")).intValue(),
            string(row, "version_id"),
            string(row, "created_by"),
            string(row, "created_at"),
            string(row, "parse_task_id"),
            string(row, "parse_status")
        );
    }

    private void audit(Identity actor, String action, long fileId) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action(action)
                .objectType("knowledge_file")
                .objectId(Long.toString(fileId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
