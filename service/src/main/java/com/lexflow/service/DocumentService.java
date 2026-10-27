package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.DocumentMapper;
import com.lexflow.dao.DocumentVersionMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.DocumentModel;
import com.lexflow.dao.model.DocumentVersionModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文书与历史版本管理；新增内容生成新版本，AI 草稿须人工通过后才能发布或导出。
 * 文书主记录的 version 用于并发控制，与正文历史版本的 versionNo 含义不同。
 */
@Service
public class DocumentService extends BaseService<DocumentModel, DocumentMapper> {

    private final DocumentVersionMapper documentVersionMapper;
    private final CaseService cases;
    private final AuditLogMapper auditLogMapper;

    public DocumentService(
        DocumentMapper mapper,
        DocumentVersionMapper documentVersionMapper,
        CaseService cases,
        AuditLogMapper auditLogMapper
    ) {
        super(mapper);
        this.documentVersionMapper = documentVersionMapper;
        this.cases = cases;
        this.auditLogMapper = auditLogMapper;
    }

    /** version 是文书修订号；currentVersionId 指向当前正文，二者都用于防止旧页面覆盖新编辑。 */
    public record DocumentView(
        String id,
        String caseId,
        String title,
        String status,
        String currentVersionId,
        int currentVersionNo,
        String createdBy,
        String createdAt,
        long version
    ) {}

    /** versionNo 是正文历史序号；AI_DRAFT 的 reviewStatus 与文书是否已发布分别管理。 */
    public record VersionView(
        String id,
        String documentId,
        int versionNo,
        String contentText,
        String source,
        String reviewStatus,
        String createdBy,
        String createdAt
    ) {}

    private void requireCaseMember(Identity identity, long caseId) {
        cases.authorize(identity, caseId, Action.READ, "document:read", false);
    }

    private void requireCaseEditor(Identity identity, long caseId) {
        cases.authorize(identity, caseId, Action.UPDATE, "document:write", true);
    }

    // ── 列表查询 ──────────────────────────────────────────────────────────────

    public List<DocumentView> listDocuments(Identity identity, long caseId) {
        return list(identity, caseId);
    }

    public List<DocumentView> list(Identity identity, long caseId) {
        requireCaseMember(identity, caseId);
        List<Map<String, Object>> rows = mapper.listWithVersion(identity.tenantId(), caseId);
        return rows.stream().map(DocumentService::toDocumentView).toList();
    }

    // ── 新建文书（含初始草稿版本）────────────────────────────────────────────

    /** 创建文书及首份人工正文，并在同一事务内设置当前正文指针。 */
    @Transactional
    public DocumentView create(
        Identity identity,
        long caseId,
        String title,
        String initialContent
    ) {
        requireCaseEditor(identity, caseId);
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new IllegalArgumentException("文书标题不能为空且长度不超过255字符");
        }

        // 1. 创建文书主记录（草稿）
        DocumentModel doc = DocumentModel.builder()
            .tenantId(identity.tenantId())
            .caseId(caseId)
            .title(title.trim())
            .status("DRAFT")
            .createdBy(identity.userId())
            .version(1L)
            .build();
        mapper.insert(doc);
        long docId = doc.getId();

        // 2. 创建初始版本（v1，MANUAL）
        DocumentVersionModel ver = DocumentVersionModel.builder()
            .tenantId(identity.tenantId())
            .documentId(docId)
            .versionNo(1)
            .contentText(initialContent != null ? initialContent : "")
            .source("MANUAL")
            .createdBy(identity.userId())
            .build();
        documentVersionMapper.insert(ver);
        long versionId = ver.getId();

        // 3. 更新 current_version_id
        mapper.update(
            null,
            new LambdaUpdateWrapper<DocumentModel>()
                .eq(DocumentModel::getTenantId, identity.tenantId())
                .eq(DocumentModel::getId, docId)
                .set(DocumentModel::getCurrentVersionId, versionId)
        );

        audit(identity, "document:create", docId, caseId);
        return getDocumentView(identity, docId);
    }

    // ── 追加新版本（手动编辑）────────────────────────────────────────────────

    /** 在客户端读取的修订号和正文 ID 仍有效时追加人工版本，并把文书重新置为草稿。 */
    @Transactional
    public VersionView addVersion(
        Identity identity,
        long caseId,
        long docId,
        String contentText,
        long expectedVersion,
        long expectedCurrentVersionId
    ) {
        requireCaseEditor(identity, caseId);
        DocumentModel doc = expectedDocument(
            identity,
            caseId,
            docId,
            expectedVersion,
            expectedCurrentVersionId
        );

        int nextVersionNo = documentVersionMapper.nextVersionNo(identity.tenantId(), docId);

        DocumentVersionModel ver = DocumentVersionModel.builder()
            .tenantId(identity.tenantId())
            .documentId(docId)
            .versionNo(nextVersionNo)
            .contentText(contentText)
            .source("MANUAL")
            .createdBy(identity.userId())
            .build();
        documentVersionMapper.insert(ver);
        long versionId = ver.getId();

        changeCurrent(doc, versionId, "DRAFT");

        audit(identity, "document:version:add", docId, caseId);
        return getVersionView(identity, versionId);
    }

    // ── 发布文书 ──────────────────────────────────────────────────────────────

    /** 发布客户端确认的当前正文；当前正文若为 AI 草稿，须已通过人工复核。 */
    @Transactional
    public DocumentView publish(
        Identity identity,
        long caseId,
        long docId,
        long expectedVersion,
        long expectedCurrentVersionId
    ) {
        requireCaseEditor(identity, caseId);
        DocumentModel doc = expectedDocument(
            identity,
            caseId,
            docId,
            expectedVersion,
            expectedCurrentVersionId
        );
        if (doc.getCurrentVersionId() != null) {
            var current = documentVersionMapper.selectById(doc.getCurrentVersionId());
            if (
                current != null &&
                "AI_DRAFT".equals(current.getSource()) &&
                !"APPROVED".equals(current.getReviewStatus())
            ) {
                throw BusinessException.conflict("AI 草稿须先通过人工复核，再发布");
            }
        }

        changeCurrent(doc, doc.getCurrentVersionId(), "PUBLISHED");

        audit(identity, "document:publish", docId, caseId);
        return getDocumentView(identity, docId);
    }

    // ── 人工复核 AI 草稿版本 ────────────────────────────────────────────────────

    /** 保存 AI 草稿的人工复核结果；通过时才切换当前正文，拒绝不会切换正文。 */
    @Transactional
    public VersionView reviewVersion(
        Identity identity,
        long caseId,
        long docId,
        long versionId,
        String reviewStatus
    ) {
        requireCaseEditor(identity, caseId);
        if (!Set.of("APPROVED", "REJECTED").contains(reviewStatus)) {
            throw new IllegalArgumentException("复核状态只能为 APPROVED 或 REJECTED");
        }
        DocumentModel doc = getDocumentRow(identity, docId, caseId);

        DocumentVersionModel ver = documentVersionMapper.selectOne(
            new LambdaQueryWrapper<DocumentVersionModel>()
                .eq(DocumentVersionModel::getTenantId, identity.tenantId())
                .eq(DocumentVersionModel::getId, versionId)
                .eq(DocumentVersionModel::getDocumentId, docId)
        );
        if (ver == null) {
            throw new IllegalArgumentException("指定的文书版本不存在");
        }
        if (!"AI_DRAFT".equals(ver.getSource())) {
            throw new IllegalStateException("只有 AI 生成的草稿版本（AI_DRAFT）才需要人工复核");
        }

        ver.setReviewStatus(reviewStatus);
        documentVersionMapper.updateById(ver);

        // 复核通过只切换当前版本，不等同于发布；切换到不同正文时文书重新进入 DRAFT。
        if ("APPROVED".equals(reviewStatus)) {
            if (!Objects.equals(doc.getCurrentVersionId(), versionId)) {
                doc.setStatus("DRAFT");
            }
            doc.setCurrentVersionId(versionId);
            // MyBatis-Plus 的 @Version 校验并递增文书主记录版本，冲突则回滚本次复核。
            if (mapper.updateById(doc) != 1) {
                throw BusinessException.conflict("文书版本已变化，请刷新后复核");
            }
        }

        audit(identity, "document:version:review", docId, caseId);
        return toVersionView(ver);
    }

    // ── 文书导出数据 ──────────────────────────────────────────────────────────

    public record ExportData(String title, String content, int versionNo, String status) {}

    /** 锁定案件后读取当前正文；expectedVersionId 非空时拒绝导出已被替换的正文。 */
    @Transactional
    public ExportData getExportData(
        Identity identity,
        long caseId,
        long docId,
        Long expectedVersionId
    ) {
        cases.authorize(identity, caseId, Action.READ, "document:read", true);
        DocumentModel doc = getDocumentRow(identity, docId, caseId);
        if (doc.getCurrentVersionId() == null) {
            throw new BusinessException(409, "文书尚无可导出的当前版本");
        }
        if (expectedVersionId != null && !expectedVersionId.equals(doc.getCurrentVersionId())) {
            throw new BusinessException(409, "文书当前版本已变化，请刷新后再导出");
        }
        DocumentVersionModel ver = documentVersionMapper.selectOne(
            new LambdaQueryWrapper<DocumentVersionModel>()
                .eq(DocumentVersionModel::getTenantId, identity.tenantId())
                .eq(DocumentVersionModel::getId, doc.getCurrentVersionId())
                .eq(DocumentVersionModel::getDocumentId, docId)
        );
        if (ver == null) {
            throw new BusinessException(409, "文书当前版本不可用，请刷新后再试");
        }
        if ("AI_DRAFT".equals(ver.getSource()) && !"APPROVED".equals(ver.getReviewStatus())) {
            throw new BusinessException(409, "AI 草稿须先通过人工复核，再导出");
        }
        audit(identity, "document:export", docId, caseId);
        return new ExportData(
            doc.getTitle(),
            ver.getContentText() != null ? ver.getContentText() : "",
            ver.getVersionNo(),
            doc.getStatus()
        );
    }

    // ── 供异步 AI 任务结果落库生成待复核草稿版本 ──────────────────────────────

    /**
     * 按 sourceTaskId 幂等追加 AI 待复核版本，不自动替换当前正文。
     * 同一任务只能复用相同文书、相同正文的已有结果，避免重复回调产生多份草稿。
     */
    @Transactional
    public VersionView createAiDraftVersion(
        Identity actor,
        long caseId,
        long docId,
        String contentText,
        long sourceTaskId
    ) {
        requireCaseEditor(actor, caseId);
        long tenantId = actor.tenantId();
        DocumentModel doc = mapper.selectOne(
            new LambdaQueryWrapper<DocumentModel>()
                .eq(DocumentModel::getTenantId, tenantId)
                .eq(DocumentModel::getId, docId)
                .eq(DocumentModel::getCaseId, caseId)
        );
        if (doc == null) {
            throw new IllegalArgumentException("文书不存在");
        }
        if ("ARCHIVED".equals(doc.getStatus())) {
            throw new AccessDenied();
        }
        var existing = documentVersionMapper.selectOne(
            new LambdaQueryWrapper<DocumentVersionModel>()
                .eq(DocumentVersionModel::getTenantId, tenantId)
                .eq(DocumentVersionModel::getSourceTaskId, sourceTaskId)
        );
        if (existing != null) {
            if (
                !Objects.equals(existing.getDocumentId(), docId) ||
                !Objects.equals(existing.getContentText(), contentText)
            ) {
                throw BusinessException.conflict("生成任务已保存不同文书结果");
            }
            return toVersionView(existing);
        }

        int nextVersionNo = documentVersionMapper.nextVersionNo(tenantId, docId);
        DocumentVersionModel ver = DocumentVersionModel.builder()
            .tenantId(tenantId)
            .documentId(docId)
            .versionNo(nextVersionNo)
            .contentText(contentText)
            .source("AI_DRAFT")
            .reviewStatus("PENDING_REVIEW")
            .sourceTaskId(sourceTaskId)
            .createdBy(actor.userId())
            .build();
        documentVersionMapper.insert(ver);
        // 仅追加待复核版本，不替换当前正文；重复回调由 sourceTaskId 对应的已保存版本处理。
        audit(actor, "document:ai-draft", docId, caseId);
        return getVersionView(actor, ver.getId());
    }

    // ── 版本列表 ──────────────────────────────────────────────────────────────

    public List<VersionView> listVersions(Identity identity, long caseId, long docId) {
        requireCaseMember(identity, caseId);
        getDocumentRow(identity, docId, caseId);

        List<DocumentVersionModel> list = documentVersionMapper.selectList(
            new LambdaQueryWrapper<DocumentVersionModel>()
                .eq(DocumentVersionModel::getTenantId, identity.tenantId())
                .eq(DocumentVersionModel::getDocumentId, docId)
                .orderByDesc(DocumentVersionModel::getVersionNo)
        );

        return list.stream().map(DocumentService::toVersionView).toList();
    }

    // ── 恢复历史版本（产生新版本）────────────────────────────────────────────

    /** 将历史正文复制为新的人工版本；历史记录保持不变，当前文书重新进入草稿状态。 */
    @Transactional
    public VersionView restoreVersion(
        Identity identity,
        long caseId,
        long docId,
        long fromVersionId,
        long expectedVersion,
        long expectedCurrentVersionId
    ) {
        requireCaseEditor(identity, caseId);
        DocumentModel doc = expectedDocument(
            identity,
            caseId,
            docId,
            expectedVersion,
            expectedCurrentVersionId
        );

        DocumentVersionModel src = documentVersionMapper.selectOne(
            new LambdaQueryWrapper<DocumentVersionModel>()
                .eq(DocumentVersionModel::getTenantId, identity.tenantId())
                .eq(DocumentVersionModel::getId, fromVersionId)
                .eq(DocumentVersionModel::getDocumentId, docId)
        );
        if (src == null) {
            throw new IllegalArgumentException("指定的历史版本不存在");
        }
        if ("AI_DRAFT".equals(src.getSource()) && !"APPROVED".equals(src.getReviewStatus())) {
            throw BusinessException.conflict("AI 草稿须先通过人工复核，才能恢复为新版本");
        }

        int nextVersionNo = documentVersionMapper.nextVersionNo(identity.tenantId(), docId);

        DocumentVersionModel newVer = DocumentVersionModel.builder()
            .tenantId(identity.tenantId())
            .documentId(docId)
            .versionNo(nextVersionNo)
            .contentText(src.getContentText())
            .source("MANUAL")
            .createdBy(identity.userId())
            .build();
        documentVersionMapper.insert(newVer);
        long newVersionId = newVer.getId();

        changeCurrent(doc, newVersionId, "DRAFT");

        audit(identity, "document:version:restore", docId, caseId);
        return getVersionView(identity, newVersionId);
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private DocumentModel expectedDocument(
        Identity actor,
        long caseId,
        long docId,
        long expectedVersion,
        long expectedCurrentVersionId
    ) {
        // 同时比较文书修订号与当前正文 ID，防止旧页面编辑或恢复时覆盖他人的新版本。
        if (expectedVersion < 0 || expectedCurrentVersionId <= 0) {
            throw BusinessException.badRequest("请提供读取文书时的版本信息");
        }
        DocumentModel doc = mapper.lockCurrent(actor.tenantId(), caseId, docId);
        if (doc == null || "ARCHIVED".equals(doc.getStatus())) {
            throw new AccessDenied();
        }
        if (
            !Objects.equals(doc.getVersion(), expectedVersion) ||
            !Objects.equals(doc.getCurrentVersionId(), expectedCurrentVersionId)
        ) {
            throw staleDocument();
        }
        if (expectedVersion == Long.MAX_VALUE) {
            throw BusinessException.conflict("文书版本已达上限");
        }
        return doc;
    }

    /** 带租户、案件、修订号和原正文 ID 更新指针；任一条件变化都交给调用方回滚事务。 */
    private void changeCurrent(DocumentModel doc, long currentVersionId, String status) {
        int changed = mapper.update(
            null,
            new LambdaUpdateWrapper<DocumentModel>()
                .eq(DocumentModel::getTenantId, doc.getTenantId())
                .eq(DocumentModel::getCaseId, doc.getCaseId())
                .eq(DocumentModel::getId, doc.getId())
                .eq(DocumentModel::getVersion, doc.getVersion())
                .eq(DocumentModel::getCurrentVersionId, doc.getCurrentVersionId())
                .set(DocumentModel::getCurrentVersionId, currentVersionId)
                .set(DocumentModel::getStatus, status)
                .set(DocumentModel::getVersion, doc.getVersion() + 1)
        );
        if (changed != 1) {
            throw staleDocument();
        }
    }

    private static BusinessException staleDocument() {
        return BusinessException.conflict("文书已被修改，请查看最新版本后合并；你的编辑尚未保存");
    }

    private DocumentModel getDocumentRow(Identity identity, long docId, long caseId) {
        DocumentModel doc = mapper.selectOne(
            new LambdaQueryWrapper<DocumentModel>()
                .eq(DocumentModel::getTenantId, identity.tenantId())
                .eq(DocumentModel::getId, docId)
                .eq(DocumentModel::getCaseId, caseId)
        );
        if (doc == null) {
            throw new AccessDenied();
        }
        return doc;
    }

    private DocumentView getDocumentView(Identity identity, long docId) {
        Map<String, Object> documentRow = mapper.getWithVersion(identity.tenantId(), docId);
        return toDocumentView(documentRow);
    }

    private VersionView getVersionView(Identity identity, long versionId) {
        DocumentVersionModel v = documentVersionMapper.selectOne(
            new LambdaQueryWrapper<DocumentVersionModel>()
                .eq(DocumentVersionModel::getTenantId, identity.tenantId())
                .eq(DocumentVersionModel::getId, versionId)
        );
        return toVersionView(v);
    }

    private static VersionView toVersionView(DocumentVersionModel v) {
        if (v == null) {
            return null;
        }
        return new VersionView(
            v.getId().toString(),
            v.getDocumentId().toString(),
            v.getVersionNo(),
            v.getContentText(),
            v.getSource(),
            v.getReviewStatus(),
            v.getCreatedBy() != null ? v.getCreatedBy().toString() : "",
            utcCreatedAt(v.getCreatedAt())
        );
    }

    private static DocumentView toDocumentView(Map<String, Object> documentRow) {
        return new DocumentView(
            documentRow.get("id").toString(),
            documentRow.get("case_id").toString(),
            (String) documentRow.get("title"),
            (String) documentRow.get("status"),
            documentRow.get("current_version_id") != null
                ? documentRow.get("current_version_id").toString()
                : null,
            ((Number) documentRow.get("current_version_no")).intValue(),
            documentRow.get("created_by").toString(),
            utcCreatedAt(documentRow.get("created_at")),
            ((Number) documentRow.get("version")).longValue()
        );
    }

    // JDBC 会话配置为 UTC；Map 查询得到 Timestamp，实体查询得到表示 UTC 的 LocalDateTime。
    // 两条读取路径都显式转成 UTC 时刻，避免 JVM 默认时区改变接口中的创建时间。
    private static String utcCreatedAt(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp.toInstant(ZoneOffset.UTC).toString();
        }
        throw new IllegalStateException("文书创建时间格式不可用");
    }

    private void audit(Identity identity, String action, long docId, long caseId) {
        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action(action)
                .objectType("document")
                .objectId(Long.toString(docId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson("{\"caseId\":\"" + caseId + "\"}")
                .build()
        );
    }
}
