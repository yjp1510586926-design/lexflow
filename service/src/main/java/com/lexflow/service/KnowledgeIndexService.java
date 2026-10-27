package com.lexflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.KnowledgeRepository.*;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService.InternalModel;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Java 掌控索引授权与发布；AI 服务计算并写入向量，成功回调仍须通过当前来源校验。 */
@Service
public class KnowledgeIndexService {

    private final KnowledgeRepository knowledge;
    private final AiConfigurationService configs;
    private final CaseService cases;
    private final AsyncTaskMapper tasks;
    private final OutboxEventMapper outbox;
    private final AuditLogMapper audits;
    private final SysUserMapper users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeAccessService access;
    private final KnowledgeVectorArtifactService vectorArtifacts;

    public KnowledgeIndexService(
        KnowledgeRepository knowledge,
        AiConfigurationService configs,
        CaseService cases,
        AsyncTaskMapper tasks,
        OutboxEventMapper outbox,
        AuditLogMapper audits,
        SysUserMapper users,
        JdbcTemplate jdbc,
        ObjectMapper json,
        KnowledgeAccessService access,
        KnowledgeVectorArtifactService vectorArtifacts
    ) {
        this.knowledge = knowledge;
        this.configs = configs;
        this.cases = cases;
        this.tasks = tasks;
        this.outbox = outbox;
        this.audits = audits;
        this.users = users;
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.vectorArtifacts = vectorArtifacts;
    }

    public record IndexRequest(String taskId, String status) {}

    public record IndexChunk(String id, String text) {}

    public record IndexInput(
        String tenantId,
        String caseId,
        String knowledgeBaseId,
        String documentId,
        String fileVersionId,
        int indexVersion,
        String scope,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        String configurationRevision,
        List<IndexChunk> chunks
    ) {
        @Override
        public String toString() {
            return "KnowledgeIndexInput[redacted]";
        }
    }

    private record Job(
        long tenantId,
        long taskId,
        long documentId,
        String modelIdentity,
        int indexVersion,
        int executionProtocol
    ) {}

    @Transactional
    public IndexRequest request(Identity actor, long baseId, long documentId) {
        if (actor == null) {
            throw new AccessDenied();
        }
        BaseRow base = access.manage(actor, baseId);
        cases.require(actor, "task:read");
        if (!knowledge.lockDocument(actor.tenantId(), baseId, documentId)) {
            throw new AccessDenied();
        }
        IndexDocumentRow document = knowledge.indexDocument(actor.tenantId(), baseId, documentId);
        if (document == null) {
            throw new AccessDenied();
        }
        if (
            !Set.of("PREPARED", "FAILED", "ACTIVE").contains(document.status()) ||
            document.pendingTaskId() != null
        ) {
            throw BusinessException.conflict("此文件已有索引任务，请刷新状态");
        }
        InternalModel config = configs.embeddingForTask(actor.tenantId());
        String identity = modelIdentity(config);
        if (identity.length() > 1000) {
            throw BusinessException.badRequest("模型配置过长");
        }
        if (
            document.lastIndexVersion() == Integer.MAX_VALUE ||
            document.indexVersion() == Integer.MAX_VALUE
        ) {
            throw BusinessException.conflict("索引版本已达上限");
        }
        int indexVersion = Math.max(
            document.indexVersion() + ("ACTIVE".equals(document.status()) ? 1 : 0),
            document.lastIndexVersion() + 1
        );
        // 每个索引版本使用新切片 ID，防止迟到或失败执行的 upsert 覆盖已发布向量。
        if (
            indexVersion != document.indexVersion() &&
            knowledge.cloneIndexChunks(
                actor.tenantId(),
                documentId,
                document.indexVersion(),
                indexVersion
            ) != document.chunkCount()
        ) {
            throw BusinessException.conflict("知识切片数量已变化");
        }

        AsyncTaskModel task = AsyncTaskModel.builder()
            .tenantId(actor.tenantId())
            .caseId(base.caseId())
            .type("KNOWLEDGE_INGEST")
            .resourceVersionId(document.fileVersionId())
            .status("QUEUED")
            .idempotencyKey(UUID.randomUUID().toString())
            .attempt(0)
            .stateVersion(1)
            .createdBy(actor.userId())
            .build();
        tasks.insert(task);
        jdbc.update(
            "INSERT INTO knowledge_index_job(tenant_id,task_id,knowledge_document_id,model_identity,index_version,execution_protocol) VALUES(?,?,?,?,?,2)",
            actor.tenantId(),
            task.getId(),
            documentId,
            identity,
            indexVersion
        );
        if (
            knowledge.beginIndex(
                actor.tenantId(),
                documentId,
                task.getId(),
                indexVersion,
                document.lastIndexVersion()
            ) != 1
        ) {
            throw BusinessException.conflict("索引状态已变化，请刷新后重试");
        }

        String eventId = UUID.randomUUID().toString();
        String traceId = TraceContext.currentOrNew();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("schemaVersion", "1.0");
        payload.put("tenantId", Long.toString(actor.tenantId()));
        payload.put("taskId", task.getId().toString());
        payload.put("caseId", base.caseId() == null ? null : Long.toString(base.caseId()));
        payload.put("scope", base.scope());
        payload.put("knowledgeBaseId", Long.toString(base.id()));
        payload.put("type", "KNOWLEDGE_INGEST");
        payload.put("resourceVersionId", Long.toString(document.fileVersionId()));
        payload.put("attempt", 0);
        payload.put("traceId", traceId);
        payload.put("occurredAt", Instant.now().toString());
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("索引任务事件序列化失败", ex);
        }
        outbox.insert(
            OutboxEventModel.builder()
                .eventId(eventId)
                .tenantId(actor.tenantId())
                .taskId(task.getId())
                .routingKey("knowledge.ingest.requested.v1")
                .payload(body)
                .status("PENDING")
                .attempt(0)
                .build()
        );
        audit(
            actor.tenantId(),
            actor.userId(),
            "knowledge:index:request",
            documentId,
            "SUCCESS",
            traceId
        );
        return new IndexRequest(task.getId().toString(), "QUEUED");
    }

    /** 内部鉴权后的索引输入入口；仍重新检查当前创建者、范围权限和文件来源。 */
    @Transactional
    public IndexInput input(long taskId, int attempt, int stateVersion) {
        return validatedInput(taskId, attempt, stateVersion, false);
    }

    @Transactional
    public void registerVectors(
        long taskId,
        int attempt,
        int stateVersion,
        String collection,
        int dimension,
        String modelIdentity
    ) {
        IndexInput current = validatedInput(taskId, attempt, stateVersion, false);
        vectorArtifacts.register(taskId, current, collection, dimension, modelIdentity);
    }

    /** 仅提供恢复资格检查输入，不把等待人工处理的任务推进为可执行状态。 */
    @Transactional(noRollbackFor = { AccessDenied.class, BusinessException.class })
    public IndexInput recoveryInput(Identity actor, long taskId, int attempt, int stateVersion) {
        Job job = job(taskId);
        if (actor == null || job == null || actor.tenantId() != job.tenantId()) {
            throw new AccessDenied();
        }
        access.manage(actor, jobBaseId(job));
        if (job.executionProtocol() != 2) {
            throw BusinessException.conflict("历史索引未记录模型执行批次，请取消后重新提交任务");
        }
        return validatedInput(taskId, attempt, stateVersion, true);
    }

    private IndexInput validatedInput(
        long taskId,
        int attempt,
        int stateVersion,
        boolean recovery
    ) {
        AsyncTaskModel task = tasks.selectLockById(taskId);
        Job job = job(taskId);
        if (
            task == null ||
            job == null ||
            !"KNOWLEDGE_INGEST".equals(task.getType()) ||
            !Objects.equals(task.getTenantId(), job.tenantId()) ||
            !(recovery
                ? "WAITING_HUMAN".equals(task.getStatus())
                : Set.of("QUEUED", "RUNNING").contains(task.getStatus())) ||
            task.getAttempt() != attempt ||
            task.getStateVersion() != stateVersion
        ) {
            throw BusinessException.conflict("索引任务已过期或不可用");
        }
        if (job.executionProtocol() != 2) {
            throw BusinessException.conflict("历史索引未记录模型执行批次，请取消后重新提交任务");
        }
        var user = users.selectById(task.getCreatedBy());
        if (user == null) {
            throw new AccessDenied();
        }
        Identity actor = new Identity(
            task.getCreatedBy(),
            task.getTenantId(),
            user.getSessionVersion(),
            user.getDisplayName()
        );
        // 来源范围授权先于索引来源读取，保持与提交流程一致的范围/文档加锁顺序。
        BaseRow base = access.manage(actor, jobBaseId(job));
        IndexDocumentRow document = knowledge.indexDocument(
            task.getTenantId(),
            base.id(),
            job.documentId()
        );
        if (
            document == null ||
            !Set.of("INDEXING", "ACTIVE").contains(document.status()) ||
            !Objects.equals(document.pendingTaskId(), taskId) ||
            document.lastIndexVersion() != job.indexVersion() ||
            !Objects.equals(document.caseId(), task.getCaseId()) ||
            document.fileVersionId() != task.getResourceVersionId()
        ) {
            throw new AccessDenied();
        }
        List<String> revisions = jdbc.queryForList(
            "SELECT revision FROM ai_configuration WHERE tenant_id=? AND purpose='EMBEDDING' FOR SHARE",
            String.class,
            task.getTenantId()
        );
        if (revisions.size() != 1 || revisions.getFirst() == null) {
            throw BusinessException.conflict("Embedding 配置已删除，请重新配置并提交索引");
        }
        String revision = revisions.getFirst();
        InternalModel config = configs.embeddingForTask(task.getTenantId());
        if (!job.modelIdentity().equals(modelIdentity(config))) {
            throw BusinessException.conflict("Embedding 配置已变化，请重新提交索引");
        }
        List<ChunkRow> chunks = knowledge.indexChunks(
            task.getTenantId(),
            document.id(),
            job.indexVersion()
        );
        if (chunks.size() != document.chunkCount() || chunks.isEmpty()) {
            throw BusinessException.conflict("知识切片数量已变化");
        }
        return new IndexInput(
            Long.toString(task.getTenantId()),
            document.caseId() == null ? null : Long.toString(document.caseId()),
            Long.toString(document.baseId()),
            Long.toString(document.id()),
            Long.toString(document.fileVersionId()),
            job.indexVersion(),
            base.scope(),
            config.provider(),
            config.baseUrl(),
            config.model(),
            config.apiKey(),
            revision,
            chunks
                .stream()
                .map(c -> new IndexChunk(Long.toString(c.id()), c.text()))
                .toList()
        );
    }

    @Transactional
    public boolean complete(
        long taskId,
        int attempt,
        int stateVersion,
        boolean success,
        String collectionName,
        Integer dimension,
        Integer indexedChunks,
        String modelIdentity
    ) {
        AsyncTaskModel task = tasks.selectLockById(taskId);
        Job job = job(taskId);
        if (task == null || job == null || !"KNOWLEDGE_INGEST".equals(task.getType())) {
            return false;
        }
        if (
            task.getAttempt() != attempt ||
            task.getStateVersion() != stateVersion ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            return false;
        }
        if (success) {
            if (
                dimension == null ||
                dimension < 2 ||
                dimension > 4096 ||
                !job.modelIdentity().equals(modelIdentity) ||
                !expectedCollection(modelIdentity, dimension).equals(collectionName)
            ) {
                throw BusinessException.badRequest("索引结果模型或维度不匹配");
            }
            try {
                IndexInput current = input(taskId, attempt, stateVersion);
                if (indexedChunks == null || indexedChunks != current.chunks().size()) {
                    throw BusinessException.badRequest("索引切片数量不匹配");
                }
                // 兼容未提前登记产物的执行端，在发布事务中补登记准确向量位置。
                vectorArtifacts.register(taskId, current, collectionName, dimension, modelIdentity);
            } catch (AccessDenied | BusinessException ex) {
                success = false;
            }
        }
        String status = success ? "SUCCEEDED" : "FAILED";
        String error = success
            ? null
            : "知识索引失败：材料、权限或模型配置已变化，或模型服务不可用";
        if (tasks.applyTaskResult(taskId, attempt, stateVersion, status, null, error) != 1) {
            return false;
        }
        // 任务终态与文档索引发布指针同事务切换，查询不会读到一半完成的发布状态。
        if (
            knowledge.finishIndex(
                task.getTenantId(),
                job.documentId(),
                taskId,
                job.indexVersion(),
                success,
                collectionName,
                dimension,
                modelIdentity
            ) != 1
        ) {
            throw new IllegalStateException("索引文档状态与任务不一致");
        }
        audit(
            task.getTenantId(),
            null,
            "knowledge:index:result",
            job.documentId(),
            status,
            TraceContext.currentOrNew()
        );
        return true;
    }

    private Job job(long taskId) {
        return jdbc.query(
            "SELECT tenant_id,task_id,knowledge_document_id,model_identity,index_version,execution_protocol FROM knowledge_index_job WHERE task_id=?",
            rs ->
                rs.next()
                    ? new Job(
                          rs.getLong(1),
                          rs.getLong(2),
                          rs.getLong(3),
                          rs.getString(4),
                          rs.getInt(5),
                          rs.getInt(6)
                      )
                    : null,
            taskId
        );
    }

    private long jobBaseId(Job job) {
        Long id = jdbc.query(
            "SELECT knowledge_base_id FROM knowledge_document WHERE tenant_id=? AND id=?",
            rs -> rs.next() ? rs.getLong(1) : null,
            job.tenantId(),
            job.documentId()
        );
        if (id == null) {
            throw new AccessDenied();
        }
        return id;
    }

    private String modelIdentity(InternalModel model) {
        try {
            return json.writeValueAsString(
                List.of(model.provider().trim(), model.baseUrl().trim(), model.model().trim())
            );
        } catch (Exception ex) {
            throw new IllegalStateException("模型标识无法序列化", ex);
        }
    }

    private static String expectedCollection(String identity, int dimension) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                identity.getBytes(StandardCharsets.UTF_8)
            );
            return (
                "lexflow_chunks_" + HexFormat.of().formatHex(hash, 0, 8) + "_" + dimension + "_v1"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("模型标识摘要失败", ex);
        }
    }

    private void audit(
        long tenantId,
        Long actorId,
        String action,
        long documentId,
        String status,
        String traceId
    ) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(tenantId)
                .actorId(actorId)
                .action(action)
                .objectType("knowledge_document")
                .objectId(Long.toString(documentId))
                .result(status)
                .traceId(traceId)
                .detailJson("{}")
                .build()
        );
    }
}
