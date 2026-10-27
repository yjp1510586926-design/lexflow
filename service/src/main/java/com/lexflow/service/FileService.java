package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.CaseMapper;
import com.lexflow.dao.CaseMemberMapper;
import com.lexflow.dao.FileCleanupMapper;
import com.lexflow.dao.FileObjectMapper;
import com.lexflow.dao.FileVersionMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.CaseModel;
import com.lexflow.dao.model.FileObjectModel;
import com.lexflow.dao.model.FileVersionModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.file.UploadInspector;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 案件文件业务入口；上传以短事务登记元数据，删除以逻辑状态阻断读取并异步清理私有对象。
 */
@Service
public class FileService extends BaseService<FileObjectModel, FileObjectMapper> {

    private final CaseService cases;
    private final FileCleanupMapper cleanup;
    private final FileVersionMapper fileVersionMapper;
    private final CaseMapper caseMapper;
    private final CaseMemberMapper caseMemberMapper;
    private final AuditLogMapper auditLogMapper;
    private final UploadMetadataService uploads;
    private final UploadInspector inspector;
    private final PrivateObjectStorage storage;

    public FileService(
        FileObjectMapper mapper,
        FileCleanupMapper cleanup,
        CaseService cases,
        FileVersionMapper fileVersionMapper,
        CaseMapper caseMapper,
        CaseMemberMapper caseMemberMapper,
        AuditLogMapper auditLogMapper,
        UploadMetadataService uploads,
        UploadInspector inspector,
        PrivateObjectStorage storage
    ) {
        super(mapper);
        this.cleanup = cleanup;
        this.cases = cases;
        this.fileVersionMapper = fileVersionMapper;
        this.caseMapper = caseMapper;
        this.caseMemberMapper = caseMemberMapper;
        this.auditLogMapper = auditLogMapper;
        this.uploads = uploads;
        this.inspector = inspector;
        this.storage = storage;
    }

    public record FileView(
        String id,
        String caseId,
        String displayName,
        String status,
        String mimeType,
        long sizeBytes,
        String sha256,
        int versionNo,
        String versionId,
        String createdBy,
        String createdAt
    ) {}

    /** 授权后打开的私有对象流及下载元数据；成功返回后由调用方负责关闭 stream。 */
    public record DownloadResult(
        InputStream stream,
        String mimeType,
        long sizeBytes,
        String displayName
    ) {}

    private void requireCaseMember(Identity identity, long caseId) {
        cases.authorize(identity, caseId, Action.READ, "file:read", false);
    }

    private void requireCaseEditor(Identity identity, long caseId, boolean deleting) {
        cases.authorize(
            identity,
            caseId,
            deleting ? Action.DELETE_FILE : Action.UPLOAD,
            deleting ? "file:delete" : "file:upload",
            true
        );
    }

    public List<FileView> listFiles(Identity identity, long caseId) {
        requireCaseMember(identity, caseId);
        List<Map<String, Object>> rows = mapper.listFilesWithVersion(identity.tenantId(), caseId);
        return rows
            .stream()
            .map(fileRow ->
                new FileView(
                    fileRow.get("id").toString(),
                    fileRow.get("case_id").toString(),
                    (String) fileRow.get("display_name"),
                    (String) fileRow.get("status"),
                    fileRow.get("mime_type") != null ? (String) fileRow.get("mime_type") : null,
                    fileRow.get("size_bytes") != null
                        ? ((Number) fileRow.get("size_bytes")).longValue()
                        : 0L,
                    fileRow.get("sha256") != null ? (String) fileRow.get("sha256") : null,
                    fileRow.get("version_no") != null
                        ? ((Number) fileRow.get("version_no")).intValue()
                        : 0,
                    fileRow.get("version_id") != null ? fileRow.get("version_id").toString() : null,
                    fileRow.get("created_by").toString(),
                    fileRow.get("created_at").toString()
                )
            )
            .toList();
    }

    /**
     * 文件检查与对象上传在数据库事务外执行，前后分别用短事务登记和发布元数据。
     * 对象上传或发布失败时保留补偿清理任务，避免在事务回滚后留下无法追踪的私有对象。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FileView upload(
        Identity identity,
        long caseId,
        Path tmpFile,
        String originalName,
        String declaredContentType
    ) throws Exception {
        // 对象存储 I/O 不持有数据库事务；独立元数据服务负责提交与失败补偿。
        cases.authorize(identity, caseId, Action.UPLOAD, "file:upload", false);
        UploadInspector.Inspected inspectedFile = inspector.inspect(
            tmpFile,
            originalName,
            declaredContentType
        );
        var pending = uploads.begin(identity, caseId, inspectedFile);
        try {
            storage.put(pending.key(), tmpFile, inspectedFile.contentType());
            uploads.complete(identity, caseId, pending.fileId());
        } catch (Exception failure) {
            try {
                uploads.fail(identity, pending.fileId());
            } catch (RuntimeException metadataFailure) {
                failure.addSuppressed(metadataFailure);
            }
            throw new IllegalStateException("文件上传失败，请稍后重试", failure);
        }
        return getFileView(identity, pending.fileId());
    }

    /** 只打开当前可用且有读取权限的对象；审计写入失败时关闭已打开的流并拒绝交付。 */
    public DownloadResult download(Identity identity, long fileId) throws Exception {
        Map<String, Object> fileRow = mapper.getDownloadInfo(identity.tenantId(), fileId);
        if (fileRow == null || fileRow.isEmpty()) {
            throw new AccessDenied();
        }
        if (!"AVAILABLE".equals(fileRow.get("status"))) {
            throw new AccessDenied();
        }

        long caseId = ((Number) fileRow.get("case_id")).longValue();
        requireCaseMember(identity, caseId);

        String objectKey = (String) fileRow.get("object_key");
        InputStream stream = storage.open(objectKey);

        try {
            audit(identity, "file:download", fileId, caseId);
        } catch (RuntimeException ex) {
            try {
                stream.close();
            } catch (Exception closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
        return new DownloadResult(
            stream,
            (String) fileRow.get("mime_type"),
            ((Number) fileRow.get("size_bytes")).longValue(),
            (String) fileRow.get("display_name")
        );
    }

    /** 同事务登记逻辑删除、审计与清理任务；对象字节由后台清理器异步删除。 */
    @Transactional
    public void delete(Identity identity, long fileId) throws Exception {
        Map<String, Object> fileRow = mapper.getFileForDelete(identity.tenantId(), fileId);
        if (fileRow == null || fileRow.isEmpty()) {
            throw new AccessDenied();
        }

        long caseId = ((Number) fileRow.get("case_id")).longValue();
        requireCaseEditor(identity, caseId, true);

        mapper.markDeleted(identity.tenantId(), fileId);
        audit(identity, "file:delete", fileId, caseId);

        // 清理任务与逻辑删除同事务提交，实际字节由清理器删除，避免回滚后留下可读但缺失的文件。
        cleanup.enqueueVersions(identity.tenantId(), fileId);
    }

    private FileView getFileView(Identity identity, long fileId) {
        Map<String, Object> fileRow = mapper.getFileViewWithVersion(identity.tenantId(), fileId);
        return new FileView(
            fileRow.get("id").toString(),
            fileRow.get("case_id").toString(),
            (String) fileRow.get("display_name"),
            (String) fileRow.get("status"),
            fileRow.get("mime_type") != null ? (String) fileRow.get("mime_type") : null,
            fileRow.get("size_bytes") != null
                ? ((Number) fileRow.get("size_bytes")).longValue()
                : 0L,
            fileRow.get("sha256") != null ? (String) fileRow.get("sha256") : null,
            fileRow.get("version_no") != null ? ((Number) fileRow.get("version_no")).intValue() : 0,
            fileRow.get("version_id") != null ? fileRow.get("version_id").toString() : null,
            fileRow.get("created_by").toString(),
            fileRow.get("created_at").toString()
        );
    }

    private void audit(Identity identity, String action, long fileId, long caseId) {
        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action(action)
                .objectType("file")
                .objectId(Long.toString(fileId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson("{\"caseId\":\"" + caseId + "\"}")
                .build()
        );
    }
}
