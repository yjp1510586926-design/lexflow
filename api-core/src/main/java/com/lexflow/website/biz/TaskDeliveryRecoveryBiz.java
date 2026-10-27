package com.lexflow.website.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.CaseAnalysisService;
import com.lexflow.service.CaseService;
import com.lexflow.service.ContractReviewService;
import com.lexflow.service.DocumentGenerationService;
import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import com.lexflow.website.core.client.IndexRecoveryEligibilityClient;
import com.lexflow.website.core.client.WorkflowRecoveryEligibilityClient;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 恢复只重新投递固定输入，不删除 AI 执行记录；尚未执行的付费步骤须另行确认资格和费用。 */
@Service
public class TaskDeliveryRecoveryBiz {

    private static final Set<String> TYPES = Set.of(
        "DOCUMENT_PARSE",
        "KNOWLEDGE_INGEST",
        "CONTRACT_REVIEW",
        "DOCUMENT_GENERATE",
        "CASE_ANALYSIS"
    );
    private static final Set<String> WORKFLOWS = Set.of(
        "CONTRACT_REVIEW",
        "DOCUMENT_GENERATE",
        "CASE_ANALYSIS"
    );
    private final AsyncTaskMapper tasks;
    private final TaskService taskService;
    private final TaskContextBiz contexts;
    private final CaseService cases;
    private final KnowledgeIndexService indexes;
    private final IndexRecoveryEligibilityClient eligibility;
    private final WorkflowRecoveryEligibilityClient workflowEligibility;
    private final ContractReviewService contracts;
    private final DocumentGenerationService generations;
    private final CaseAnalysisService analyses;
    private final OutboxEventMapper outbox;
    private final AuditLogMapper audits;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public TaskDeliveryRecoveryBiz(
        AsyncTaskMapper tasks,
        TaskService taskService,
        TaskContextBiz contexts,
        CaseService cases,
        KnowledgeIndexService indexes,
        IndexRecoveryEligibilityClient eligibility,
        OutboxEventMapper outbox,
        AuditLogMapper audits,
        JdbcTemplate db,
        ObjectMapper json,
        WorkflowRecoveryEligibilityClient workflowEligibility,
        ContractReviewService contracts,
        DocumentGenerationService generations,
        CaseAnalysisService analyses
    ) {
        this.tasks = tasks;
        this.taskService = taskService;
        this.contexts = contexts;
        this.cases = cases;
        this.indexes = indexes;
        this.eligibility = eligibility;
        this.workflowEligibility = workflowEligibility;
        this.contracts = contracts;
        this.generations = generations;
        this.analyses = analyses;
        this.outbox = outbox;
        this.audits = audits;
        this.db = db;
        this.json = json;
    }

    /** canRecover 描述读取时的资格；实际恢复仍须重新校验权限、执行记录和状态版本。 */
    public record RecoveryView(
        String taskId,
        String type,
        String status,
        int attempt,
        int stateVersion,
        String deliveryStatus,
        int retryCount,
        String failureKind,
        String deadLetteredAt,
        boolean canRecover,
        String recoveryMessage
    ) {}

    /** sourceVersion 是原投递版本，registeredVersion 是登记死信后的任务版本，不能互换。 */
    private record Ledger(
        int attempt,
        String status,
        int retryCount,
        String kind,
        String deadAt,
        Integer registeredVersion,
        Integer sourceVersion
    ) {}

    /** 返回当前投递状态，并向 AI 执行账本核对付费任务是否具备安全恢复资格。 */
    @Transactional
    public RecoveryView view(Identity actor, long taskId) {
        taskService.get(actor, taskId);
        var task = tasks.selectByIdForUpdate(actor.tenantId(), taskId);
        return toView(actor, task, ledger(actor.tenantId(), taskId));
    }

    /**
     * 恢复经过核验的死信轮次，提升 attempt/stateVersion 并登记新 Outbox。
     * requestId 绑定本次恢复意图；响应丢失时使用原标识重试，不产生第二次投递。
     */
    @Transactional
    public RecoveryView recover(
        Identity actor,
        long taskId,
        int stateVersion,
        String requestId,
        boolean confirmModelCosts
    ) {
        taskService.get(actor, taskId);
        var task = tasks.selectByIdForUpdate(actor.tenantId(), taskId);
        requireSource(actor, task);
        if (
            stateVersion < 1 ||
            requestId == null ||
            !requestId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            )
        ) {
            throw BusinessException.badRequest("请提供有效的状态版本和恢复请求标识");
        }
        String intent = UUID.fromString(requestId).toString();
        // 响应丢失后客户端可使用原请求标识重试；相同标识必须绑定同一操作者、状态版本和费用确认。
        var old = db.queryForList(
            "SELECT task_id,actor_id,from_state_version,confirm_model_costs FROM task_delivery_recovery_request WHERE tenant_id=? AND request_id=?",
            actor.tenantId(),
            intent
        );
        if (!old.isEmpty()) {
            var row = old.getFirst();
            if (
                number(row.get("task_id")) != taskId ||
                number(row.get("actor_id")) != actor.userId() ||
                number(row.get("from_state_version")) != stateVersion ||
                bool(row.get("confirm_model_costs")) != confirmModelCosts
            ) {
                throw BusinessException.conflict("恢复请求标识已用于不同输入");
            }
            return toView(actor, task, ledger(actor.tenantId(), taskId));
        }
        Ledger ledger = ledger(actor.tenantId(), taskId);
        requireDead(task, ledger, stateVersion);
        if ("UNCERTAIN".equals(ledger.kind())) {
            throw BusinessException.conflict(
                "执行结果不确定，禁止重新投递；请取消任务并核对模型执行记录"
            );
        }
        Map<String, Object> source = WORKFLOWS.contains(task.getType())
            ? Map.of("scope", "CASE")
            : contexts.get(taskId);
        if ("KNOWLEDGE_INGEST".equals(task.getType())) {
            if (!confirmModelCosts) {
                throw BusinessException.badRequest("请确认未执行的 Embedding 批次可能产生模型费用");
            }
            var input = indexes.recoveryInput(
                actor,
                taskId,
                task.getAttempt(),
                task.getStateVersion()
            );
            String safety = eligibility.check(taskId, input);
            if (!"SAFE".equals(safety)) {
                throw BusinessException.conflict(safetyMessage(safety));
            }
        }
        if (WORKFLOWS.contains(task.getType())) {
            if (!confirmModelCosts) {
                throw BusinessException.badRequest("请确认尚未执行的步骤可能产生模型费用");
            }
            String safety = workflowSafety(actor, task);
            if (!"SAFE".equals(safety)) {
                throw BusinessException.conflict(safetyMessage(safety));
            }
        }
        if (task.getAttempt() == Integer.MAX_VALUE || task.getStateVersion() == Integer.MAX_VALUE) {
            throw BusinessException.conflict("任务执行版本已达上限");
        }
        int attempt = task.getAttempt() + 1;
        // 任务轮次、状态版本、恢复记录与 Outbox 同事务提交，防止只恢复状态却没有可投递事件。
        int updated = db.update(
            """
            UPDATE async_task SET status='QUEUED',attempt=attempt+1,state_version=state_version+1,
              error_message=NULL,result_object_key=NULL,started_at=NULL,finished_at=NULL
            WHERE tenant_id=? AND id=? AND status='WAITING_HUMAN' AND attempt=? AND state_version=?
            """,
            actor.tenantId(),
            taskId,
            task.getAttempt(),
            stateVersion
        );
        if (updated != 1) {
            throw BusinessException.conflict("任务状态已变化，请刷新");
        }
        if ("CONTRACT_REVIEW".equals(task.getType())) {
            contracts.clearDeliveryWaiting(task);
        }
        if ("CASE_ANALYSIS".equals(task.getType())) {
            analyses.clearDeliveryWaiting(task);
        }
        String eventId = UUID.randomUUID().toString(),
            trace = TraceContext.currentOrNew();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("schemaVersion", "1.0");
        payload.put("tenantId", Long.toString(actor.tenantId()));
        payload.put("taskId", Long.toString(taskId));
        payload.put("caseId", task.getCaseId() == null ? null : task.getCaseId().toString());
        payload.put("scope", source.get("scope"));
        Object base = source.get("knowledge_base_id");
        if ("KNOWLEDGE_INGEST".equals(task.getType())) {
            base = db.queryForObject(
                """
                SELECT d.knowledge_base_id FROM knowledge_index_job j JOIN knowledge_document d
                  ON d.tenant_id=j.tenant_id AND d.id=j.knowledge_document_id WHERE j.tenant_id=? AND j.task_id=?
                """,
                Long.class,
                actor.tenantId(),
                taskId
            );
        }
        payload.put("knowledgeBaseId", base == null ? null : base.toString());
        payload.put("type", task.getType());
        payload.put("resourceVersionId", task.getResourceVersionId().toString());
        payload.put("attempt", attempt);
        payload.put("stateVersion", stateVersion + 1);
        payload.put("traceId", trace);
        payload.put("occurredAt", Instant.now().toString());
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("恢复任务事件序列化失败", ex);
        }
        outbox.insert(
            OutboxEventModel.builder()
                .eventId(eventId)
                .tenantId(actor.tenantId())
                .taskId(taskId)
                .routingKey(route(task.getType()))
                .payload(body)
                .status("PENDING")
                .attempt(0)
                .build()
        );
        db.update(
            "UPDATE task_delivery_recovery SET delivery_status='RESOLVED',recovered_at=NOW(6) WHERE tenant_id=? AND task_id=?",
            actor.tenantId(),
            taskId
        );
        try {
            db.update(
                "INSERT INTO task_delivery_recovery_request(tenant_id,task_id,request_id,actor_id,from_state_version,confirm_model_costs,recovered_attempt) VALUES(?,?,?,?,?,?,?)",
                actor.tenantId(),
                taskId,
                intent,
                actor.userId(),
                stateVersion,
                confirmModelCosts,
                attempt
            );
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("恢复请求标识已被使用，请刷新");
        }
        audit(
            task.getTenantId(),
            actor.userId(),
            taskId,
            "task:delivery:recover",
            "SUCCESS",
            trace
        );
        task.setAttempt(attempt);
        task.setStateVersion(stateVersion + 1);
        task.setStatus("QUEUED");
        return toView(actor, task, ledger(actor.tenantId(), taskId));
    }

    /**
     * 消息还须匹配真实 Outbox 的固定输入和执行轮次，避免伪造事件改变任务状态。
     * 返回 true 表示消息可确认；已核验的旧轮次也会返回 true，但不会变更当前任务。
     */
    @Transactional
    public boolean acceptDeadLetter(String queue, JsonNode event, int retryCount, String kind) {
        String type = switch (queue) {
            case "lexflow.q.document.parse", "lexflow.q.document.parse.dlq" -> "DOCUMENT_PARSE";
            case
                "lexflow.q.knowledge.ingest",
                "lexflow.q.knowledge.ingest.dlq" -> "KNOWLEDGE_INGEST";
            case "lexflow.q.contract.review", "lexflow.q.contract.review.dlq" -> "CONTRACT_REVIEW";
            case
                "lexflow.q.document.generate",
                "lexflow.q.document.generate.dlq" -> "DOCUMENT_GENERATE";
            case "lexflow.q.case.analysis", "lexflow.q.case.analysis.dlq" -> "CASE_ANALYSIS";
            default -> null;
        };
        if (type == null || event == null || !event.isObject()) {
            return false;
        }
        long tenant, taskId;
        try {
            tenant = id(event.path("tenantId").asText());
            taskId = id(event.path("taskId").asText());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        var task = tasks.selectByIdForUpdate(tenant, taskId);
        if (
            task == null ||
            !type.equals(task.getType()) ||
            !event.path("attempt").isInt() ||
            event.path("attempt").asInt() < 0
        ) {
            return false;
        }
        var originals = db.queryForList(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=? AND event_id=? AND routing_key=?",
            String.class,
            tenant,
            taskId,
            event.path("eventId").asText(),
            route(type)
        );
        if (originals.size() != 1) {
            return false;
        }
        try {
            JsonNode original = json.readTree(originals.getFirst());
            if (original.isTextual()) {
                original = json.readTree(original.asText());
            }
            for (String field : List.of(
                "eventId",
                "schemaVersion",
                "tenantId",
                "taskId",
                "caseId",
                "scope",
                "knowledgeBaseId",
                "type",
                "resourceVersionId",
                "attempt",
                "stateVersion"
            )) {
                if (!Objects.equals(original.get(field), event.get(field))) {
                    return false;
                }
            }
            if (
                !task
                    .getResourceVersionId()
                    .toString()
                    .equals(event.path("resourceVersionId").asText())
            ) {
                return false;
            }
        } catch (IOException ex) {
            return false;
        }
        if (WORKFLOWS.contains(type)) {
            if (!event.path("stateVersion").isInt() || event.path("stateVersion").asInt() < 1) {
                return false;
            }
            int sourceVersion = event.path("stateVersion").asInt();
            if (task.getAttempt() != event.path("attempt").asInt()) {
                return true;
            }
            Ledger old = ledger(tenant, taskId);
            boolean duplicate =
                "WAITING_HUMAN".equals(task.getStatus()) &&
                old != null &&
                "DEAD".equals(old.status()) &&
                Objects.equals(old.registeredVersion(), task.getStateVersion()) &&
                Objects.equals(old.sourceVersion(), sourceVersion);
            boolean current =
                (Set.of("QUEUED", "RUNNING").contains(task.getStatus()) &&
                    task.getStateVersion() == sourceVersion) ||
                ("RUNNING".equals(task.getStatus()) &&
                    sourceVersion < Integer.MAX_VALUE &&
                    task.getStateVersion() == sourceVersion + 1);
            if (!duplicate && !current) {
                return true;
            } // 已绑定的旧事件可确认，但不能改变后续执行轮次。
            registerDead(tenant, taskId, task.getAttempt(), retryCount, kind, sourceVersion);
        } else {
            deadLetter(tenant, taskId, event.path("attempt").asInt(), retryCount, kind);
        }
        return true;
    }

    @Transactional
    public void deadLetter(
        long tenant,
        long taskId,
        int attempt,
        int retryCount,
        String failureKind
    ) {
        registerDead(tenant, taskId, attempt, retryCount, failureKind, null);
    }

    private void registerDead(
        long tenant,
        long taskId,
        int attempt,
        int retryCount,
        String failureKind,
        Integer sourceVersion
    ) {
        var task = tasks.selectByIdForUpdate(tenant, taskId);
        if (
            task == null ||
            !TYPES.contains(task.getType()) ||
            task.getAttempt() != attempt ||
            (WORKFLOWS.contains(task.getType()) && sourceVersion == null)
        ) {
            return;
        }
        Ledger old = ledger(tenant, taskId);
        // 付费任务缺少可靠分类时视为 UNCERTAIN，重复死信只能提升风险等级，不能降低风险。
        String kind = Set.of("TRANSIENT", "PERMANENT", "UNCERTAIN").contains(
            Objects.toString(failureKind, "")
        )
            ? failureKind
            : !"DOCUMENT_PARSE".equals(task.getType())
              ? "UNCERTAIN"
              : "PERMANENT";
        int retries = Math.max(0, Math.min(3, retryCount));
        if (
            old != null &&
            old.attempt() == attempt &&
            "DEAD".equals(old.status()) &&
            "WAITING_HUMAN".equals(task.getStatus())
        ) {
            String merged = rank(kind) > rank(old.kind()) ? kind : old.kind();
            if (!merged.equals(old.kind()) || retries > old.retryCount()) {
                db.update(
                    "UPDATE task_delivery_recovery SET failure_kind=?,retry_count=GREATEST(retry_count,?) WHERE tenant_id=? AND task_id=?",
                    merged,
                    retries,
                    tenant,
                    taskId
                );
                if ("CONTRACT_REVIEW".equals(task.getType())) {
                    contracts.markDeliveryWaiting(task, merged);
                }
                if ("CASE_ANALYSIS".equals(task.getType())) {
                    analyses.markDeliveryWaiting(task, merged);
                }
                audit(
                    tenant,
                    null,
                    taskId,
                    "task:delivery:classification",
                    "SUCCESS",
                    TraceContext.currentOrNew()
                );
            }
            return;
        }
        if (!Set.of("QUEUED", "RUNNING").contains(task.getStatus())) {
            return;
        }
        if (task.getStateVersion() == Integer.MAX_VALUE) {
            throw new IllegalStateException("任务状态版本已达上限");
        }
        db.update(
            "UPDATE async_task SET status='WAITING_HUMAN',state_version=state_version+1,error_message=? WHERE tenant_id=? AND id=?",
            "投递重试已停止，请查看处理记录后恢复或取消任务",
            tenant,
            taskId
        );
        if ("CONTRACT_REVIEW".equals(task.getType())) {
            contracts.markDeliveryWaiting(task, kind);
        }
        if ("CASE_ANALYSIS".equals(task.getType())) {
            analyses.markDeliveryWaiting(task, kind);
        }
        db.update(
            """
            INSERT INTO task_delivery_recovery(tenant_id,task_id,attempt,delivery_status,retry_count,failure_kind,registered_state_version,source_state_version)
            VALUES(?,?,?,'DEAD',?,?,?,?) ON DUPLICATE KEY UPDATE attempt=VALUES(attempt),delivery_status='DEAD',
              retry_count=VALUES(retry_count),failure_kind=VALUES(failure_kind),registered_state_version=VALUES(registered_state_version),source_state_version=VALUES(source_state_version),dead_lettered_at=NOW(6),recovered_at=NULL
            """,
            tenant,
            taskId,
            attempt,
            retries,
            kind,
            task.getStateVersion() + 1,
            sourceVersion
        );
        audit(tenant, null, taskId, "task:delivery:dead", "SUCCESS", TraceContext.currentOrNew());
    }

    private RecoveryView toView(Identity actor, AsyncTaskModel task, Ledger ledger) {
        boolean can = false;
        String message = null;
        if (
            ledger != null &&
            "DEAD".equals(ledger.status()) &&
            "WAITING_HUMAN".equals(task.getStatus()) &&
            ledger.attempt() == task.getAttempt() &&
            (!WORKFLOWS.contains(task.getType()) ||
                Objects.equals(ledger.registeredVersion(), task.getStateVersion()))
        ) {
            try {
                requireSource(actor, task);
                if ("UNCERTAIN".equals(ledger.kind())) {
                    message = "执行结果不确定，禁止恢复；请取消并核对执行记录";
                } else if ("KNOWLEDGE_INGEST".equals(task.getType())) {
                    var input = indexes.recoveryInput(
                        actor,
                        task.getId(),
                        task.getAttempt(),
                        task.getStateVersion()
                    );
                    String safety = eligibility.check(task.getId(), input);
                    can = "SAFE".equals(safety);
                    if (!can) {
                        message = safetyMessage(safety);
                    }
                } else if (WORKFLOWS.contains(task.getType())) {
                    String safety = workflowSafety(actor, task);
                    can = "SAFE".equals(safety);
                    if (!can) {
                        message = safetyMessage(safety);
                    }
                } else {
                    can = true;
                }
            } catch (AccessDenied ex) {
                message = "当前权限或材料状态不允许恢复";
            } catch (BusinessException ex) {
                message = ex.getCode() == 409 ? ex.getMessage() : "当前权限或材料状态不允许恢复";
            }
        }
        return new RecoveryView(
            task.getId().toString(),
            task.getType(),
            task.getStatus(),
            task.getAttempt(),
            task.getStateVersion(),
            ledger == null ? "NONE" : ledger.status(),
            ledger == null ? 0 : ledger.retryCount(),
            ledger == null ? null : ledger.kind(),
            ledger == null ? null : ledger.deadAt(),
            can,
            message
        );
    }

    private void requireSource(Identity actor, AsyncTaskModel task) {
        cases.require(actor, "task:cancel");
        if (!TYPES.contains(task.getType())) {
            throw BusinessException.conflict("此任务类型不支持投递恢复");
        }
        if (WORKFLOWS.contains(task.getType())) {
            switch (task.getType()) {
                case "CONTRACT_REVIEW" -> contracts.currentRecoveryInput(actor, task.getId());
                case "DOCUMENT_GENERATE" -> generations.currentRecoveryInput(actor, task.getId());
                case "CASE_ANALYSIS" -> analyses.currentRecoveryInput(actor, task.getId());
                default -> throw new IllegalStateException();
            }
            return;
        }
        if (task.getCaseId() != null) {
            cases.authorize(actor, task.getCaseId(), Action.UPDATE, "file:read", true);
        }
        contexts.get(task.getId()); // 重新检查创建人的当前权限、固定文件版本及独立的租户知识库归属。
    }

    private void requireDead(AsyncTaskModel task, Ledger ledger, int stateVersion) {
        if (
            ledger == null ||
            !"DEAD".equals(ledger.status()) ||
            ledger.attempt() != task.getAttempt() ||
            !"WAITING_HUMAN".equals(task.getStatus()) ||
            task.getStateVersion() != stateVersion ||
            (WORKFLOWS.contains(task.getType()) &&
                !Objects.equals(ledger.registeredVersion(), stateVersion))
        ) {
            throw BusinessException.conflict("任务状态已变化或无需恢复，请刷新");
        }
    }

    private Ledger ledger(long tenant, long taskId) {
        return db.query(
            "SELECT attempt,delivery_status,retry_count,failure_kind,dead_lettered_at,registered_state_version,source_state_version FROM task_delivery_recovery WHERE tenant_id=? AND task_id=? FOR UPDATE",
            resultSet ->
                resultSet.next()
                    ? new Ledger(
                          resultSet.getInt(1),
                          resultSet.getString(2),
                          resultSet.getInt(3),
                          resultSet.getString(4),
                          resultSet.getTimestamp(5).toInstant().toString(),
                          (Integer) resultSet.getObject(6),
                          (Integer) resultSet.getObject(7)
                      )
                    : null,
            tenant,
            taskId
        );
    }

    private String workflowSafety(Identity actor, AsyncTaskModel task) {
        Object input = switch (task.getType()) {
            case "CONTRACT_REVIEW" -> contracts.recoveryInput(
                actor,
                task.getId(),
                task.getAttempt(),
                task.getStateVersion()
            );
            case "DOCUMENT_GENERATE" -> generations.recoveryInput(
                actor,
                task.getId(),
                task.getAttempt(),
                task.getStateVersion()
            );
            case "CASE_ANALYSIS" -> analyses.recoveryInput(
                actor,
                task.getId(),
                task.getAttempt(),
                task.getStateVersion()
            );
            default -> throw new IllegalStateException();
        };
        List<?> evidence = "CONTRACT_REVIEW".equals(task.getType())
            ? contracts.recoveryEvidence(actor, task.getId())
            : null;
        return workflowEligibility.check(task.getType(), task.getId(), input, evidence);
    }

    private void audit(
        long tenant,
        Long actor,
        long task,
        String action,
        String result,
        String trace
    ) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(tenant)
                .actorId(actor)
                .action(action)
                .objectType("task")
                .objectId(Long.toString(task))
                .result(result)
                .traceId(trace)
                .detailJson("{}")
                .build()
        );
    }

    private static String route(String type) {
        return switch (type) {
            case "DOCUMENT_PARSE" -> "document.parse.requested.v1";
            case "KNOWLEDGE_INGEST" -> "knowledge.ingest.requested.v1";
            case "CONTRACT_REVIEW" -> "contract.review.requested.v1";
            case "DOCUMENT_GENERATE" -> "document.generate.requested.v1";
            case "CASE_ANALYSIS" -> "case.analysis.requested.v1";
            default -> throw new IllegalArgumentException("Unsupported delivery type");
        };
    }

    private static int rank(String kind) {
        return "UNCERTAIN".equals(kind) ? 3 : "PERMANENT".equals(kind) ? 2 : 1;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : number(value) != 0;
    }

    private static long id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value);
    }

    private static String safetyMessage(String status) {
        return switch (Objects.toString(status, "")) {
            case "BUSY" -> "任务仍在执行，请稍后刷新处理记录";
            case "UNCERTAIN", "FAILED" -> "模型执行记录不允许重发，请取消并核对执行记录";
            case "INPUT_CHANGED" -> "材料或模型配置已变化，请取消后重新提交任务";
            default -> "无法确认模型执行记录，请稍后刷新处理记录";
        };
    }
}
