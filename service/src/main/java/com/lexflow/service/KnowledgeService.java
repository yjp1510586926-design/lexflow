package com.lexflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.KnowledgeRepository.*;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.TaskResultService.SourceSegment;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理案件或律所公共知识库，把已解析的固定文件版本整理为带来源定位的待索引文档。 */
@Service
public class KnowledgeService {

    private static final int CHUNK_SIZE = 800;
    private static final int OVERLAP = 80;
    private final KnowledgeRepository repository;
    private final CaseService cases;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final KnowledgeAccessService access;

    public KnowledgeService(
        KnowledgeRepository repository,
        CaseService cases,
        AuditLogMapper audits,
        ObjectMapper json,
        KnowledgeAccessService access
    ) {
        this.repository = repository;
        this.cases = cases;
        this.audits = audits;
        this.json = json;
        this.access = access;
    }

    public record BaseView(String id, String caseId, String name, String scope, String status) {}

    /** PREPARED 仅表示正文与切片已入库；索引任务及其模型身份由独立的索引流程管理。 */
    public record DocumentView(
        String id,
        String fileVersionId,
        String parseTaskId,
        String fileName,
        int fileVersion,
        String status,
        int chunkCount,
        String pendingTaskId
    ) {}

    public record ChunkView(
        String id,
        int chunkNo,
        String locatorKind,
        int locatorIndex,
        int startOffset,
        int endOffset,
        String text
    ) {}

    @Transactional
    public BaseView create(Identity actor, long caseId, String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("知识库名称须为 1–200 个字符");
        }
        cases.authorize(actor, caseId, Action.UPDATE, "knowledge:manage", true);
        long id = repository.createBase(actor.tenantId(), caseId, normalized, actor.userId());
        audit(actor, "knowledge:base:create", id);
        return new BaseView(Long.toString(id), Long.toString(caseId), normalized, "CASE", "ACTIVE");
    }

    @Transactional
    public BaseView createTenant(Identity actor, String name) {
        access.requireTenantManager(actor);
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw BusinessException.badRequest("知识库名称须为 1–200 个字符");
        }
        long id;
        try {
            id = repository.createTenantBase(actor.tenantId(), normalized, actor.userId());
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("本律所已有同名公共知识库");
        }
        audit(actor, "knowledge:base:create", id);
        return new BaseView(Long.toString(id), null, normalized, "TENANT", "ACTIVE");
    }

    public Map<String, Object> listTenant(Identity actor) {
        cases.require(actor, "knowledge:read");
        return Map.of(
            "canManage",
            access.canManageTenant(actor),
            "items",
            repository
                .tenantBases(actor.tenantId())
                .stream()
                .map(KnowledgeService::baseView)
                .toList()
        );
    }

    public List<BaseView> list(Identity actor, long caseId) {
        cases.authorize(actor, caseId, Action.READ, "case:read", false);
        return repository
            .bases(actor.tenantId(), caseId)
            .stream()
            .map(KnowledgeService::baseView)
            .toList();
    }

    public BaseView get(Identity actor, long baseId) {
        BaseRow base = access.read(actor, baseId);
        return baseView(base);
    }

    /**
     * 按文件版本复用已准备的知识文档；新文档必须有解析正文和有效页段定位。
     * 案件与公共知识分别读取其绑定来源，不能仅凭 parseTaskId 把其他范围的材料导入知识库。
     */
    @Transactional
    public DocumentView prepare(Identity actor, long baseId, long parseTaskId) {
        BaseRow base = access.manage(actor, baseId);
        if (!"ACTIVE".equals(base.status())) {
            throw new IllegalStateException("知识库已归档");
        }

        SourceRow source = "TENANT".equals(base.scope())
            ? repository.tenantSource(actor.tenantId(), baseId, parseTaskId)
            : repository.source(actor.tenantId(), base.caseId(), parseTaskId);
        if (source == null) {
            throw new AccessDenied();
        }
        DocumentRow existing = repository.documentForVersion(
            actor.tenantId(),
            baseId,
            source.fileVersionId()
        );
        if (existing != null) {
            return documentView(existing);
        }
        if (source.segmentsJson() == null) {
            throw new IllegalArgumentException("此解析结果没有来源位置，请重新提取文字");
        }

        List<SourceSegment> segments;
        try {
            segments = json.readValue(source.segmentsJson(), new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("解析结果来源位置无效", ex);
        }
        List<NewChunk> chunks = split(source.text(), segments);
        long documentId = repository.createDocument(
            actor.tenantId(),
            baseId,
            source,
            chunks.size(),
            actor.userId()
        );
        repository.insertChunks(actor.tenantId(), documentId, chunks);
        audit(actor, "knowledge:document:prepare", documentId);
        return new DocumentView(
            Long.toString(documentId),
            Long.toString(source.fileVersionId()),
            Long.toString(source.taskId()),
            source.fileName(),
            source.fileVersion(),
            "PREPARED",
            chunks.size(),
            null
        );
    }

    public List<DocumentView> documents(Identity actor, long baseId) {
        BaseRow base = access.read(actor, baseId);
        return repository
            .documents(actor.tenantId(), baseId)
            .stream()
            .map(KnowledgeService::documentView)
            .toList();
    }

    public List<ChunkView> chunks(Identity actor, long baseId, long documentId) {
        BaseRow base = access.read(actor, baseId);
        return repository
            .chunks(actor.tenantId(), baseId, documentId)
            .stream()
            .map(c ->
                new ChunkView(
                    Long.toString(c.id()),
                    c.chunkNo(),
                    c.kind(),
                    c.locatorIndex(),
                    c.startOffset(),
                    c.endOffset(),
                    c.text()
                )
            )
            .toList();
    }

    private static BaseView baseView(BaseRow row) {
        return new BaseView(
            Long.toString(row.id()),
            row.caseId() == null ? null : Long.toString(row.caseId()),
            row.name(),
            row.scope(),
            row.status()
        );
    }

    private static DocumentView documentView(DocumentRow row) {
        return new DocumentView(
            Long.toString(row.id()),
            Long.toString(row.fileVersionId()),
            Long.toString(row.parseTaskId()),
            row.fileName(),
            row.fileVersion(),
            row.status(),
            row.chunkCount(),
            row.pendingTaskId() == null ? null : Long.toString(row.pendingTaskId())
        );
    }

    /** 在每个页段内分片并保留原文绝对字符位置，引用可直接回到固定解析正文。 */
    static List<NewChunk> split(String text, List<SourceSegment> segments) {
        if (
            text == null ||
            text.isBlank() ||
            segments == null ||
            segments.isEmpty() ||
            segments.size() > 5000
        ) {
            throw new IllegalArgumentException("没有可入库的解析正文或来源位置");
        }
        List<NewChunk> chunks = new ArrayList<>();
        int previousEnd = 0;
        int previousIndex = 0;
        for (SourceSegment segment : segments) {
            if (
                segment == null ||
                !("PAGE".equals(segment.kind()) || "PARAGRAPH".equals(segment.kind())) ||
                segment.index() <= previousIndex ||
                segment.startOffset() < previousEnd ||
                segment.endOffset() > text.length() ||
                segment.endOffset() <= segment.startOffset()
            ) {
                throw new IllegalArgumentException("来源位置与解析原文不一致");
            }
            int start = segment.startOffset();
            while (start < segment.endOffset()) {
                // 相邻切片保留重叠上下文，边界避开 UTF-16 代理对，避免拆断补充字符。
                int end = Math.min(start + CHUNK_SIZE, segment.endOffset());
                if (end < segment.endOffset() && Character.isHighSurrogate(text.charAt(end - 1))) {
                    end--;
                }
                String content = text.substring(start, end);
                if (!content.isBlank()) {
                    chunks.add(
                        new NewChunk(
                            chunks.size() + 1,
                            segment.kind(),
                            segment.index(),
                            start,
                            end,
                            content
                        )
                    );
                    if (chunks.size() > 10000) {
                        throw new IllegalArgumentException("文件切片超过 10000 个上限");
                    }
                }
                if (end == segment.endOffset()) {
                    break;
                }
                start = end - OVERLAP;
                if (Character.isLowSurrogate(text.charAt(start))) {
                    start--;
                }
            }
            previousEnd = segment.endOffset();
            previousIndex = segment.index();
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("解析正文没有可入库的文字");
        }
        return chunks;
    }

    private void audit(Identity actor, String action, long objectId) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action(action)
                .objectType("knowledge")
                .objectId(Long.toString(objectId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson("{}")
                .build()
        );
    }
}
