package com.lexflow.service;

import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.KnowledgeVectorArtifactRepository;
import com.lexflow.dao.KnowledgeVectorArtifactRepository.Artifact;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeVectorArtifactService {

    private final KnowledgeVectorArtifactRepository artifacts;
    private final AsyncTaskMapper tasks;
    private final JdbcTemplate db;
    private final AuditLogMapper audits;

    public KnowledgeVectorArtifactService(
        KnowledgeVectorArtifactRepository artifacts,
        AsyncTaskMapper tasks,
        JdbcTemplate db,
        AuditLogMapper audits
    ) {
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.db = db;
        this.audits = audits;
    }

    public static String collectionName(String model, int dimension) {
        if (
            model == null ||
            model.isBlank() ||
            model.length() > 1000 ||
            dimension < 2 ||
            dimension > 4096
        ) {
            throw BusinessException.badRequest("向量位置无效");
        }
        try {
            return (
                "lexflow_chunks_" +
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                        model.getBytes(StandardCharsets.UTF_8)
                    ),
                    0,
                    8
                ) +
                "_" +
                dimension +
                "_v1"
            );
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 索引服务完成授权并锁定输入后登记准确向量位置，登记先于外部 upsert。 */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
    public void register(
        long taskId,
        KnowledgeIndexService.IndexInput input,
        String collection,
        int dimension,
        String model
    ) {
        if (!collectionName(model, dimension).equals(collection)) {
            throw BusinessException.badRequest("向量位置与模型不匹配");
        }
        String expected;
        try {
            expected = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                List.of(input.provider().trim(), input.baseUrl().trim(), input.model().trim())
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (!expected.equals(model)) {
            throw BusinessException.conflict("索引模型已变化");
        }
        long tenant = Long.parseLong(input.tenantId()),
            document = Long.parseLong(input.documentId());
        var current = artifacts.location(tenant, document, input.indexVersion(), collection);
        Long caseId = input.caseId() == null ? null : Long.valueOf(input.caseId());
        if (current != null) {
            if (
                current.retired() ||
                current.baseId() != Long.parseLong(input.knowledgeBaseId()) ||
                !Objects.equals(current.caseId(), caseId) ||
                !current.scope().equals(input.scope()) ||
                current.fileVersionId() != Long.parseLong(input.fileVersionId()) ||
                current.dimension() != dimension ||
                !current.modelIdentity().equals(model) ||
                !Objects.equals(current.taskId(), taskId)
            ) {
                throw BusinessException.conflict("向量位置已退役或不属于当前任务");
            }
            return;
        }
        artifacts.insert(
            tenant,
            Long.parseLong(input.knowledgeBaseId()),
            caseId,
            input.scope(),
            document,
            Long.parseLong(input.fileVersionId()),
            input.indexVersion(),
            taskId,
            collection,
            dimension,
            model
        );
    }

    public List<Long> candidates() {
        return artifacts.candidates(10);
    }

    /** 先提交退役标记再删除外部向量，已退役位置不可被后续执行端重新占用。 */
    @Transactional
    public Artifact reserve(long id) {
        var hint = artifacts.find(id);
        if (hint == null) {
            return null;
        }
        if (hint.taskId() != null) {
            tasks.selectLockById(hint.taskId());
        }
        db.query(
            "SELECT id FROM knowledge_document WHERE tenant_id=? AND id=? FOR UPDATE",
            r -> {
                return r.next();
            },
            hint.tenantId(),
            hint.documentId()
        );
        var a = artifacts.lock(id);
        if (a == null) {
            return null;
        }
        // 判断清理资格时持有来源行锁，核对发布指针及来源归属，保护当前发布或执行中的向量。
        var states = db.query(
            """
            SELECT d.status,d.index_version,d.collection_name,d.embedding_dimension,d.model_identity,
              d.pending_index_task_id,d.last_index_version,b.status,b.scope,b.case_id,v.sha256,d.source_sha256,f.status,
              f.scope,f.case_id,f.knowledge_base_id
            FROM knowledge_document d JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
            WHERE d.tenant_id=? AND d.id=? AND d.knowledge_base_id=? AND d.file_version_id=? FOR SHARE
            """,
            (r, n) -> new Object[] {
                r.getString(1),
                r.getInt(2),
                r.getString(3),
                r.getObject(4, Integer.class),
                r.getString(5),
                r.getObject(6, Long.class),
                r.getInt(7),
                r.getString(8),
                r.getString(9),
                r.getObject(10, Long.class),
                r.getString(11),
                r.getString(12),
                r.getString(13),
                r.getString(14),
                r.getObject(15, Long.class),
                r.getObject(16, Long.class),
            },
            a.tenantId(),
            a.documentId(),
            a.baseId(),
            a.fileVersionId()
        );
        boolean retain = false;
        if (states.size() == 1) {
            var s = states.getFirst();
            boolean source =
                "ACTIVE".equals(s[7]) &&
                a.scope().equals(s[8]) &&
                Objects.equals(a.caseId(), s[9]) &&
                Objects.equals(s[10], s[11]) &&
                "AVAILABLE".equals(s[12]) &&
                a.scope().equals(s[13]) &&
                ("CASE".equals(a.scope())
                    ? Objects.equals(a.caseId(), s[14]) && s[15] == null
                    : s[14] == null && Objects.equals(a.baseId(), s[15]));
            boolean published =
                "ACTIVE".equals(s[0]) &&
                Objects.equals(a.indexVersion(), s[1]) &&
                a.collectionName().equals(s[2]) &&
                Objects.equals(a.dimension(), s[3]) &&
                a.modelIdentity().equals(s[4]);
            boolean pending =
                Objects.equals(a.taskId(), s[5]) &&
                Objects.equals(a.indexVersion(), s[6]) &&
                a.taskId() != null &&
                Boolean.TRUE.equals(
                    db.query(
                        "SELECT status IN ('QUEUED','RUNNING','WAITING_HUMAN') FROM async_task WHERE tenant_id=? AND id=? AND type='KNOWLEDGE_INGEST'",
                        r -> r.next() ? r.getBoolean(1) : false,
                        a.tenantId(),
                        a.taskId()
                    )
                );
            // 墓碑也须重查有效发布，防止管理恢复后误删；退役标记仍禁止执行端复用该位置。
            retain = source && (published || (!a.retired() && pending));
        }
        if (retain) {
            artifacts.retain(id);
            return null;
        }
        // 历史位置无法由模型标识准确还原时保留记录，不猜测并删除其他向量集合。
        if (!collectionName(a.modelIdentity(), a.dimension()).equals(a.collectionName())) {
            artifacts.retain(id);
            return null;
        }
        String token = UUID.randomUUID().toString();
        artifacts.reserve(id, token);
        if (!a.retired()) {
            audit(a, "knowledge:vector:retire", "SUCCESS");
        }
        return new Artifact(
            a.id(),
            a.tenantId(),
            a.baseId(),
            a.caseId(),
            a.scope(),
            a.documentId(),
            a.fileVersionId(),
            a.indexVersion(),
            a.taskId(),
            a.collectionName(),
            a.dimension(),
            a.modelIdentity(),
            true,
            token,
            a.attempts()
        );
    }

    @Transactional
    public void finish(Artifact a, boolean success) {
        int delay = success ? 86400 : (int) Math.min(3600, 10L << Math.min(9, a.attempts()));
        if (artifacts.finish(a.id(), a.leaseToken(), success, delay) == 1) {
            audit(a, "knowledge:vector:cleanup", success ? "SUCCESS" : "FAILED");
        }
    }

    private void audit(Artifact a, String action, String result) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(a.tenantId())
                .action(action)
                .objectType("knowledge_document")
                .objectId(Long.toString(a.documentId()))
                .result(result)
                .traceId(TraceContext.currentOrNew())
                .detailJson("{\"indexVersion\":" + a.indexVersion() + "}")
                .build()
        );
    }
}
