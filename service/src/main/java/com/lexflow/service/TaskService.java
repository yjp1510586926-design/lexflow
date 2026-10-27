package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysRoleMapper;
import com.lexflow.dao.TaskParseResultMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登记文件解析任务，并提供各业务异步任务的统一查询与取消入口。
 * 案件任务与律所公共知识任务按各自来源重新授权，任务创建记录不代表永久访问权。
 * 新任务与 Outbox 事件同事务提交；attempt 区分执行轮次，stateVersion 阻止旧状态操作覆盖新状态。
 */
@Service
public class TaskService extends BaseService<AsyncTaskModel, AsyncTaskMapper> {

    private final SysRoleMapper sysRoleMapper;
    private final AuditLogMapper auditLogMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper json;
    private final TaskParseResultMapper parseResults;
    private final CaseService cases;
    private final KnowledgeAccessService knowledge;

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> ALLOWED_TYPES = Set.of("DOCUMENT_PARSE");

    public TaskService(
        AsyncTaskMapper mapper,
        SysRoleMapper sysRoleMapper,
        AuditLogMapper auditLogMapper,
        OutboxEventMapper outboxEventMapper,
        ObjectMapper json,
        TaskParseResultMapper parseResults,
        CaseService cases,
        KnowledgeAccessService knowledge
    ) {
        super(mapper);
        this.sysRoleMapper = sysRoleMapper;
        this.auditLogMapper = auditLogMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.json = json != null ? json : new ObjectMapper();
        this.parseResults = parseResults;
        this.cases = cases;
        this.knowledge = knowledge;
    }

    /** 对外任务摘要；所有 ID 使用字符串，避免前端数字精度丢失，时间使用 UTC 文本。 */
    public record TaskView(
        String id,
        String caseId,
        String resourceVersionId,
        String type,
        String status,
        int attempt,
        String errorMessage,
        String createdBy,
        String startedAt,
        String finishedAt,
        String createdAt
    ) {}

    public record TaskPage(List<TaskView> items, long total, int page, int size) {}

    // ── 任务提交（Outbox 事务模式）──────────────────────────────────────────

    /** 仅开放案件文件解析；请求标识只能复用同一创建者、案件和固定文件版本的任务。 */
    @Transactional
    public TaskView submitTask(
        Identity identity,
        Long caseId,
        String type,
        Long resourceVersionId,
        String idempotencyKey
    ) {
        if (identity == null) {
            throw new AccessDenied();
        }
        if (type == null || !ALLOWED_TYPES.contains(type.trim())) {
            throw new IllegalArgumentException("该任务类型尚未开放: " + type);
        }
        String taskType = type.trim();

        if (caseId == null || resourceVersionId == null || resourceVersionId <= 0) {
            throw new IllegalArgumentException("请选择案件中的文件版本后再提交任务");
        }
        cases.require(identity, "task:read");
        cases.authorize(identity, caseId, Action.READ, "file:read", false);

        return createParseTask(identity, caseId, resourceVersionId, idempotencyKey, null);
    }

    /** 律所公共知识仅接受独立上传且绑定该知识库的来源，caseId 为空不能作为授权依据。 */
    @Transactional
    public TaskView submitKnowledgeParse(
        Identity identity,
        long baseId,
        long resourceVersionId,
        String idempotencyKey
    ) {
        var base = knowledge.manage(identity, baseId);
        if (!"TENANT".equals(base.scope()) || base.caseId() != null) {
            throw new AccessDenied();
        }
        cases.require(identity, "task:read");
        if (
            resourceVersionId <= 0 ||
            mapper.countTenantSource(identity.tenantId(), baseId, resourceVersionId) != 1
        ) {
            throw new AccessDenied();
        }
        return createParseTask(identity, null, resourceVersionId, idempotencyKey, baseId);
    }

    /** 调用方完成来源授权并开启事务后调用；任务记录、投递事件和审计共享该事务。 */
    private TaskView createParseTask(
        Identity identity,
        Long caseId,
        Long resourceVersionId,
        String idempotencyKey,
        Long baseId
    ) {
        String taskType = "DOCUMENT_PARSE";
        String normalizedIdempotencyKey =
            idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey.trim()
                : UUID.randomUUID().toString();

        if (normalizedIdempotencyKey.length() > 128) {
            throw new IllegalArgumentException("请求标识不能超过 128 个字符");
        }

        // 只有相同操作者和相同输入才允许复用已提交任务。
        AsyncTaskModel existing = mapper.selectOne(
            new LambdaQueryWrapper<AsyncTaskModel>()
                .eq(AsyncTaskModel::getTenantId, identity.tenantId())
                .eq(AsyncTaskModel::getIdempotencyKey, normalizedIdempotencyKey)
        );
        if (existing != null) {
            if (
                !Objects.equals(existing.getCreatedBy(), identity.userId()) ||
                !Objects.equals(existing.getCaseId(), caseId) ||
                !Objects.equals(existing.getType(), taskType) ||
                !Objects.equals(existing.getResourceVersionId(), resourceVersionId)
            ) {
                throw BusinessException.conflict("请求标识已用于其他任务，请使用新的请求标识");
            }
            return toTaskView(existing);
        }

        if (
            caseId != null &&
            mapper.countAvailableSource(identity.tenantId(), caseId, resourceVersionId) != 1
        ) {
            throw new AccessDenied();
        }

        // 1. 插入 async_task 主记录（状态 QUEUED，state_version=1）
        AsyncTaskModel task = AsyncTaskModel.builder()
            .tenantId(identity.tenantId())
            .caseId(caseId)
            .type(taskType)
            .resourceVersionId(resourceVersionId)
            .status("QUEUED")
            .idempotencyKey(normalizedIdempotencyKey)
            .attempt(0)
            .stateVersion(1)
            .createdBy(identity.userId())
            .build();
        mapper.insert(task);
        long taskId = task.getId();

        // 2. 映射 Outbox 路由键
        String routingKey = switch (taskType) {
            case "DOCUMENT_PARSE" -> "document.parse.requested.v1";
            case "CONTRACT_REVIEW" -> "contract.review.requested.v1";
            case "KNOWLEDGE_INGEST" -> "knowledge.ingest.requested.v1";
            case "EXPORT" -> "document.export.requested.v1";
            default -> "task.general.requested.v1";
        };

        String eventId = UUID.randomUUID().toString();
        String traceId = TraceContext.currentOrNew();
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventId", eventId);
        payloadMap.put("schemaVersion", "1.0");
        payloadMap.put("tenantId", Long.toString(identity.tenantId()));
        payloadMap.put("taskId", Long.toString(taskId));
        payloadMap.put("caseId", caseId != null ? Long.toString(caseId) : null);
        payloadMap.put("scope", baseId == null ? "CASE" : "TENANT");
        payloadMap.put("knowledgeBaseId", baseId == null ? null : Long.toString(baseId));
        payloadMap.put("type", taskType);
        payloadMap.put(
            "resourceVersionId",
            resourceVersionId != null ? Long.toString(resourceVersionId) : ""
        );
        payloadMap.put("attempt", 0);
        payloadMap.put("traceId", traceId);
        payloadMap.put("occurredAt", Instant.now().toString());

        String payloadJson;
        try {
            payloadJson = json.writeValueAsString(payloadMap);
        } catch (Exception ex) {
            throw new IllegalStateException("任务事件序列化失败", ex);
        }

        // 3. 在同一本地事务内写入 outbox_event（状态 PENDING）
        OutboxEventModel outbox = OutboxEventModel.builder()
            .eventId(eventId)
            .tenantId(identity.tenantId())
            .taskId(taskId)
            .routingKey(routingKey)
            .payload(payloadJson)
            .status("PENDING")
            .attempt(0)
            // 首次投递时间由 MySQL 默认值提供，与发布器的 NOW(6) 使用同一时钟。
            .build();
        outboxEventMapper.insert(outbox);

        // 4. 写入审计日志
        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action("task:submit")
                .objectType("task")
                .objectId(Long.toString(taskId))
                .result("SUCCESS")
                .traceId(traceId)
                .detailJson("{\"type\":\"" + taskType + "\",\"caseId\":\"" + caseId + "\"}")
                .build()
        );

        return toTaskView(task);
    }

    // ── 列表查询 ──────────────────────────────────────────────────────────────

    public TaskPage list(Identity identity, int page, int size, String type, String status) {
        return list(identity, page, size, null, type, status);
    }

    /**
     * 案件任务与公共知识任务分别应用可见性条件；非管理员只能看到本人创建的任务。
     * 案件成员关系和公共知识管理权限按查询时的状态计算，不沿用提交任务时的权限。
     */
    public TaskPage list(
        Identity identity,
        int page,
        int size,
        Long caseId,
        String type,
        String status
    ) {
        cases.require(identity, "task:read");
        boolean caseReadable = true;
        try {
            cases.require(identity, "case:read");
        } catch (AccessDenied denied) {
            caseReadable = false;
        }
        boolean tenantManageable = knowledge.canManageTenant(identity);
        if (size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
        if (page < 1) {
            page = 1;
        }

        boolean admin = sysRoleMapper.isUserAdmin(identity.tenantId(), identity.userId()) > 0;

        LambdaQueryWrapper<AsyncTaskModel> taskQuery = new LambdaQueryWrapper<AsyncTaskModel>().eq(
            AsyncTaskModel::getTenantId,
            identity.tenantId()
        );

        // 分别限定案件任务与公共知识任务；普通成员还必须是任务创建者且仍属于该案件。
        if (!caseReadable) {
            taskQuery.isNull(AsyncTaskModel::getCaseId);
        }
        if (!tenantManageable) {
            taskQuery.isNotNull(AsyncTaskModel::getCaseId);
        } else {
            taskQuery.and(scopeQuery ->
                scopeQuery
                    .isNotNull(AsyncTaskModel::getCaseId)
                    .or()
                    .exists(
                        """
                        SELECT 1 FROM file_version v
                        JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
                        JOIN knowledge_base b ON b.tenant_id=f.tenant_id AND b.id=f.knowledge_base_id
                        WHERE v.tenant_id=async_task.tenant_id AND v.id=async_task.resource_version_id
                          AND f.scope='TENANT' AND f.case_id IS NULL AND f.status='AVAILABLE'
                          AND b.scope='TENANT' AND b.case_id IS NULL AND b.status='ACTIVE'
                          AND async_task.type IN ('DOCUMENT_PARSE','KNOWLEDGE_INGEST')
                        """
                    )
            );
        }
        if (!admin) {
            taskQuery.eq(AsyncTaskModel::getCreatedBy, identity.userId());
            taskQuery.and(scopeQuery ->
                scopeQuery
                    .isNull(AsyncTaskModel::getCaseId)
                    .or()
                    .exists(
                        """
                        SELECT 1 FROM case_member cm JOIN case_info c
                        ON c.tenant_id = cm.tenant_id AND c.id = cm.case_id
                        WHERE cm.tenant_id = async_task.tenant_id
                        AND cm.case_id = async_task.case_id AND cm.user_id = {0}
                        """,
                        identity.userId()
                    )
            );
        }
        if (caseId != null) {
            taskQuery.eq(AsyncTaskModel::getCaseId, caseId);
        }
        if (type != null && !type.isBlank()) {
            taskQuery.eq(AsyncTaskModel::getType, type.trim());
        }
        if (status != null && !status.isBlank()) {
            taskQuery.eq(AsyncTaskModel::getStatus, status.trim());
        }
        taskQuery.orderByDesc(AsyncTaskModel::getCreatedAt).orderByDesc(AsyncTaskModel::getId);

        Page<AsyncTaskModel> taskPage = mapper.selectPage(new Page<>(page, size), taskQuery);
        List<TaskView> items = taskPage.getRecords().stream().map(TaskService::toTaskView).toList();

        return new TaskPage(items, taskPage.getTotal(), page, size);
    }

    // ── 详情 ──────────────────────────────────────────────────────────────────

    public TaskView get(Identity identity, long taskId) {
        cases.require(identity, "task:read");
        AsyncTaskModel task = mapper.selectOne(
            new LambdaQueryWrapper<AsyncTaskModel>()
                .eq(AsyncTaskModel::getTenantId, identity.tenantId())
                .eq(AsyncTaskModel::getId, taskId)
        );
        if (task == null) {
            throw new AccessDenied();
        }

        boolean admin = sysRoleMapper.isUserAdmin(identity.tenantId(), identity.userId()) > 0;
        if (!admin && (task.getCreatedBy() == null || identity.userId() != task.getCreatedBy())) {
            throw new AccessDenied();
        }
        authorizeTaskSource(identity, task);
        return toTaskView(task);
    }

    /** 只交付已成功的解析结果，并将保存的定位 JSON 转为 segments 集合供引用定位使用。 */
    public Map<String, Object> parsedText(Identity identity, long taskId) {
        TaskView task = get(identity, taskId);
        if (!"DOCUMENT_PARSE".equals(task.type()) || !"SUCCEEDED".equals(task.status())) {
            throw BusinessException.conflict("任务尚无可查看的解析结果");
        }
        if (task.caseId() != null) {
            cases.authorize(
                identity,
                Long.parseLong(task.caseId()),
                Action.READ,
                "file:read",
                false
            );
        }
        // get() 已重新检查公共知识来源及当前管理权限，不能凭历史任务绕过来源授权。
        Map<String, Object> result = parseResults.find(identity.tenantId(), taskId);
        if (result == null) {
            throw BusinessException.notFound("解析结果不存在");
        }
        Object segmentsJson = result.remove("segmentsJson");
        if (segmentsJson != null) {
            try {
                result.put("segments", json.readValue(segmentsJson.toString(), List.class));
            } catch (Exception ex) {
                throw new IllegalStateException("解析结果来源位置损坏", ex);
            }
        }
        return result;
    }

    // ── 取消请求（尽力执行，终态拒绝）────────────────────────────────────────

    /**
     * 根据任务类型选择取消路径：专用流程直接作废，普通案件解析请求执行端停止。
     * 所有路径使用当前状态版本更新；取消后迟到的结果不能覆盖新的任务状态。
     */
    @Transactional
    public TaskView requestCancel(Identity identity, long taskId) {
        cases.require(identity, "task:cancel");
        cases.require(identity, "task:read");
        AsyncTaskModel taskModel = mapper.selectByIdForUpdate(identity.tenantId(), taskId);
        if (taskModel == null) {
            throw new AccessDenied();
        }

        boolean admin = sysRoleMapper.isUserAdmin(identity.tenantId(), identity.userId()) > 0;
        Long createdBy = taskModel.getCreatedBy();
        if (!admin && (createdBy == null || createdBy != identity.userId())) {
            throw new AccessDenied();
        }
        authorizeTaskSource(identity, taskModel);

        String currentStatus = taskModel.getStatus();
        if (TERMINAL.contains(currentStatus)) {
            throw new IllegalStateException("任务已处于终态（" + currentStatus + "），无法取消");
        }
        if ("CANCEL_REQUESTED".equals(currentStatus)) {
            throw new IllegalStateException("取消请求已提交，请等待任务确认停止");
        }

        int stateVersion = taskModel.getStateVersion() != null ? taskModel.getStateVersion() : 1;
        // 索引等专用流程及待人工处理的解析直接取消并推进状态版本，迟到回调不能再发布结果；
        // 其余案件解析先置 CANCEL_REQUESTED，等待执行端确认停止。
        boolean indexTask = "KNOWLEDGE_INGEST".equals(taskModel.getType());
        boolean publicParse =
            taskModel.getCaseId() == null && "DOCUMENT_PARSE".equals(taskModel.getType());
        boolean waitingParse =
            "DOCUMENT_PARSE".equals(taskModel.getType()) && "WAITING_HUMAN".equals(currentStatus);
        int updated = waitingParse
            ? mapper.cancelWaitingParse(identity.tenantId(), taskId, stateVersion)
            : publicParse
              ? mapper.cancelTenantParse(identity.tenantId(), taskId, stateVersion)
              : indexTask
                ? mapper.cancelKnowledgeIndex(identity.tenantId(), taskId, stateVersion)
                : "CONTRACT_REVIEW".equals(taskModel.getType())
                  ? mapper.cancelContractReview(identity.tenantId(), taskId, stateVersion)
                  : "CASE_ANALYSIS".equals(taskModel.getType())
                    ? mapper.cancelCaseAnalysis(identity.tenantId(), taskId, stateVersion)
                    : "DOCUMENT_GENERATE".equals(taskModel.getType())
                      ? mapper.cancelDocumentGeneration(identity.tenantId(), taskId, stateVersion)
                      : "EXPORT".equals(taskModel.getType())
                        ? mapper.cancelExport(identity.tenantId(), taskId, stateVersion)
                        : mapper.requestCancel(identity.tenantId(), taskId, stateVersion);
        if (updated == 0) {
            throw new ConcurrentModificationException("任务状态已变更，请刷新后重试");
        }
        if (indexTask && mapper.failKnowledgeDocumentOnCancel(identity.tenantId(), taskId) != 1) {
            throw new IllegalStateException("索引文档状态与任务不一致");
        }

        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action("task:cancel")
                .objectType("task")
                .objectId(Long.toString(taskId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );

        return get(identity, taskId);
    }

    private void authorizeTaskSource(Identity identity, AsyncTaskModel task) {
        // 历史任务的创建者身份并不保留来源访问权，每次读取或取消仍重新授权。
        if (task.getCaseId() != null) {
            cases.authorize(identity, task.getCaseId(), Action.READ, "case:read", false);
            return;
        }
        Long baseId = mapper.tenantSourceBaseForTask(identity.tenantId(), task.getId());
        if (baseId == null) {
            throw new AccessDenied();
        }
        var base = knowledge.manage(identity, baseId);
        if (!"TENANT".equals(base.scope()) || base.caseId() != null) {
            throw new AccessDenied();
        }
    }

    private static TaskView toTaskView(AsyncTaskModel taskModel) {
        return new TaskView(
            taskModel.getId().toString(),
            taskModel.getCaseId() != null ? taskModel.getCaseId().toString() : null,
            taskModel.getResourceVersionId() != null
                ? taskModel.getResourceVersionId().toString()
                : null,
            taskModel.getType(),
            taskModel.getStatus(),
            taskModel.getAttempt() != null ? taskModel.getAttempt() : 0,
            taskModel.getErrorMessage(),
            taskModel.getCreatedBy() != null ? taskModel.getCreatedBy().toString() : null,
            taskModel.getStartedAt() != null
                ? taskModel.getStartedAt().toInstant(ZoneOffset.UTC).toString()
                : null,
            taskModel.getFinishedAt() != null
                ? taskModel.getFinishedAt().toInstant(ZoneOffset.UTC).toString()
                : null,
            taskModel.getCreatedAt() != null
                ? taskModel.getCreatedAt().toInstant(ZoneOffset.UTC).toString()
                : null
        );
    }
}
