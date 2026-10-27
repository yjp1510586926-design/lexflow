package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.DocumentGenerationRepository;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 固定来源版本、生成要求、案件上下文及模型修订；生成结果另存待复核版本，再由文书流程发布。 */
@Service
public class DocumentGenerationService {

    private final AsyncTaskMapper tasks;
    private final DocumentGenerationRepository generations;
    private final DocumentService documents;
    private final CaseService cases;
    private final TaskService taskViews;
    private final AiConfigurationService configs;
    private final SysUserMapper users;
    private final OutboxEventMapper outbox;
    private final AuditLogMapper audits;
    private final ObjectMapper json;

    public DocumentGenerationService(
        AsyncTaskMapper tasks,
        DocumentGenerationRepository generations,
        DocumentService documents,
        CaseService cases,
        TaskService taskViews,
        AiConfigurationService configs,
        SysUserMapper users,
        OutboxEventMapper outbox,
        AuditLogMapper audits,
        ObjectMapper json
    ) {
        this.tasks = tasks;
        this.generations = generations;
        this.documents = documents;
        this.cases = cases;
        this.taskViews = taskViews;
        this.configs = configs;
        this.users = users;
        this.outbox = outbox;
        this.audits = audits;
        this.json = json;
    }

    public record Requested(String taskId, String status, int stateVersion) {}

    public record View(
        String taskId,
        String status,
        int stateVersion,
        String documentId,
        String sourceVersionId,
        String instructions,
        String errorMessage,
        DocumentService.VersionView result,
        List<String> missingInformation
    ) {}

    public record Input(
        String tenantId,
        String taskId,
        String caseId,
        String documentId,
        String sourceVersionId,
        int attempt,
        int stateVersion,
        String title,
        String baseContent,
        String instructions,
        String caseContext,
        String modelRevision,
        String provider,
        String baseUrl,
        String model,
        String apiKey
    ) {
        @Override
        public String toString() {
            return "DocumentGenerationInput[redacted]";
        }
    }

    @Transactional
    public Requested request(
        Identity actor,
        long caseId,
        long documentId,
        long sourceVersion,
        String instructions,
        String key
    ) {
        authorize(actor, caseId, true);
        if (
            sourceVersion <= 0 ||
            instructions == null ||
            instructions.isBlank() ||
            instructions.length() > 2000 ||
            key == null ||
            key.isBlank() ||
            key.length() > 128
        ) {
            throw BusinessException.badRequest("请填写 2000 字以内生成要求及请求标识");
        }
        instructions = instructions.trim();
        key = key.trim();
        var source = generations.source(actor.tenantId(), caseId, documentId, sourceVersion);
        if (source == null) {
            throw new AccessDenied();
        }
        if (source.content().length() > 30000) {
            throw BusinessException.badRequest("当前正文超过 3 万字，请先拆分文书");
        }
        configs.llmForQuestion(actor.tenantId());
        String revision = generations.revision(actor.tenantId());
        var old = tasks.selectOne(
            new LambdaQueryWrapper<AsyncTaskModel>()
                .eq(AsyncTaskModel::getTenantId, actor.tenantId())
                .eq(AsyncTaskModel::getIdempotencyKey, key)
        );
        if (old != null) {
            var g = generations.get(actor.tenantId(), old.getId());
            if (
                g == null ||
                !"DOCUMENT_GENERATE".equals(old.getType()) ||
                !Objects.equals(old.getCreatedBy(), actor.userId()) ||
                !Objects.equals(old.getCaseId(), caseId) ||
                g.documentId() != documentId ||
                g.sourceVersionId() != sourceVersion ||
                !g.instructions().equals(instructions) ||
                !g.modelRevision().equals(revision)
            ) {
                throw BusinessException.conflict("请求标识已用于不同生成输入，请使用新的标识");
            }
            return requested(old);
        }
        var current = documents
            .list(actor, caseId)
            .stream()
            .filter(d -> d.id().equals(Long.toString(documentId)))
            .findFirst()
            .orElseThrow(AccessDenied::new);
        if (!Objects.equals(current.currentVersionId(), Long.toString(sourceVersion))) {
            throw BusinessException.conflict("文书当前版本已变化，请刷新后提交");
        }
        var c = cases.get(actor, caseId);
        // 保存提交时的案件上下文，恢复投递时沿用该快照，不随案件后续编辑改变模型输入。
        var context = new LinkedHashMap<String, Object>();
        context.put("caseNo", c.caseNo());
        context.put("title", c.title());
        context.put("type", c.type());
        context.put("description", c.description());
        var task = AsyncTaskModel.builder()
            .tenantId(actor.tenantId())
            .caseId(caseId)
            .type("DOCUMENT_GENERATE")
            .resourceVersionId(sourceVersion)
            .idempotencyKey(key)
            .status("QUEUED")
            .attempt(0)
            .stateVersion(1)
            .createdBy(actor.userId())
            .build();
        try {
            tasks.insert(task);
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.conflict("请求已同时提交，请刷新后重试");
        }
        generations.create(
            actor.tenantId(),
            task.getId(),
            documentId,
            sourceVersion,
            instructions,
            encode(context),
            revision
        );
        event(task);
        audit(task, actor.userId(), "document:generate:submit");
        return requested(task);
    }

    public View view(Identity actor, long id) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, false);
        var g = generation(task);
        cases.authorize(actor, task.getCaseId(), Action.READ, "document:read", false);
        DocumentService.VersionView result = null;
        if (g.resultVersionId() != null) {
            result = documents
                .listVersions(actor, task.getCaseId(), g.documentId())
                .stream()
                .filter(v -> v.id().equals(g.resultVersionId().toString()))
                .findFirst()
                .orElseThrow(AccessDenied::new);
        }
        return new View(
            task.getId().toString(),
            task.getStatus(),
            task.getStateVersion(),
            Long.toString(g.documentId()),
            Long.toString(g.sourceVersionId()),
            g.instructions(),
            task.getErrorMessage(),
            result,
            decodeMissing(g.missingInformation())
        );
    }

    @Transactional
    public Requested recover(Identity actor, long id, int stateVersion) {
        taskViews.get(actor, id);
        var t = task(actor.tenantId(), id, true);
        authorize(actor, t.getCaseId(), true);
        if (
            t.getStateVersion() != stateVersion ||
            !Set.of("QUEUED", "RUNNING").contains(t.getStatus())
        ) {
            throw BusinessException.conflict("仅可恢复排队或执行中的生成任务");
        }
        input(id, t.getAttempt(), t.getStateVersion());
        event(t);
        audit(t, actor.userId(), "document:generate:recover");
        return requested(t);
    }

    @Transactional
    public Input begin(long id, int attempt, int version) {
        var t = tasks.selectLockById(id);
        if (t == null) {
            throw new AccessDenied();
        }
        execution(t, attempt, version, true);
        authorize(creator(t), t.getCaseId(), true);
        if ("QUEUED".equals(t.getStatus())) {
            if (
                generations.transition(
                    t.getTenantId(),
                    id,
                    attempt,
                    t.getStateVersion(),
                    "RUNNING",
                    null
                ) != 1
            ) {
                throw BusinessException.conflict("生成任务状态已变化");
            }
        }
        return input(
            id,
            attempt,
            "QUEUED".equals(t.getStatus()) ? t.getStateVersion() + 1 : t.getStateVersion()
        );
    }

    public Input input(long id, int attempt, int version) {
        var t = tasks.selectById(id);
        if (t == null) {
            throw BusinessException.notFound("生成任务不存在");
        }
        execution(t, attempt, version, true);
        return fixedInput(t, attempt, true);
    }

    private Input fixedInput(AsyncTaskModel t, int attempt, boolean includeKey) {
        // 执行或恢复前重新检查创建者权限、来源版本和模型修订，旧任务不能沿用已失效权限。
        authorize(creator(t), t.getCaseId(), false);
        var g = generation(t);
        var source = generations.source(
            t.getTenantId(),
            t.getCaseId(),
            g.documentId(),
            g.sourceVersionId()
        );
        if (source == null) {
            throw new AccessDenied();
        }
        var model = configs.llmForQuestion(t.getTenantId());
        if (!g.modelRevision().equals(generations.revision(t.getTenantId()))) {
            throw BusinessException.conflict("模型配置已变化，请重新提交生成");
        }
        return new Input(
            t.getTenantId().toString(),
            t.getId().toString(),
            t.getCaseId().toString(),
            Long.toString(g.documentId()),
            Long.toString(g.sourceVersionId()),
            attempt,
            t.getStateVersion(),
            source.title(),
            source.content(),
            g.instructions(),
            g.caseContext(),
            g.modelRevision(),
            model.provider(),
            model.baseUrl(),
            model.model(),
            includeKey ? model.apiKey() : null
        );
    }

    public Input recoveryInput(Identity actor, long id, int attempt, int registeredStateVersion) {
        taskViews.get(actor, id);
        var t = task(actor.tenantId(), id, true);
        var g = generation(t);
        if (g.executionProtocol() != 2) {
            throw BusinessException.conflict("历史任务无法确认模型记账，请取消并重新提交");
        }
        if (
            t.getAttempt() != attempt ||
            t.getStateVersion() != registeredStateVersion ||
            !"WAITING_HUMAN".equals(t.getStatus()) ||
            !generations.currentDead(t.getTenantId(), id, attempt, registeredStateVersion)
        ) {
            throw BusinessException.conflict("生成投递状态已变化");
        }
        return currentRecoveryInput(actor, id);
    }

    public Input currentRecoveryInput(Identity actor, long id) {
        taskViews.get(actor, id);
        var t = task(actor.tenantId(), id, true);
        cases.require(actor, "task:cancel");
        authorize(actor, t.getCaseId(), true);
        authorize(creator(t), t.getCaseId(), false);
        return fixedInput(t, t.getAttempt(), false);
    }

    @Transactional
    public boolean apply(
        long tenant,
        long id,
        int attempt,
        int version,
        String outcome,
        String content,
        List<String> missing
    ) {
        var t = tasks.selectByIdForUpdate(tenant, id);
        if (
            t == null ||
            !"DOCUMENT_GENERATE".equals(t.getType()) ||
            t.getAttempt() != attempt ||
            t.getStateVersion() != version ||
            !"RUNNING".equals(t.getStatus())
        ) {
            return false;
        }
        String status,
            error = null;
        switch (outcome == null ? "" : outcome) {
            case "FAILED" -> {
                status = "FAILED";
                error = "文书生成失败，请检查模型、材料及服务状态";
            }
            case "UNCERTAIN" -> {
                status = "WAITING_HUMAN";
                error = "模型请求结果不确定，不会自动重发；请取消后确认是否提交新的生成任务";
            }
            case "SUCCEEDED" -> {
                // 任务成功表示 AI 草稿已保存，发布和导出仍须经过文书版本的人工复核门槛。
                status = "SUCCEEDED";
                var actor = creator(t);
                authorize(actor, t.getCaseId(), true);
                var g = generation(t);
                input(id, attempt, version);
                if (
                    content == null ||
                    content.isBlank() ||
                    content.length() > 30000 ||
                    missing == null ||
                    missing.size() > 10 ||
                    missing.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 200)
                ) {
                    throw BusinessException.badRequest("生成文书内容或待补信息无效");
                }
                var result = documents.createAiDraftVersion(
                    actor,
                    t.getCaseId(),
                    g.documentId(),
                    content.trim(),
                    id
                );
                generations.result(tenant, id, Long.parseLong(result.id()), encode(missing));
            }
            default -> throw BusinessException.badRequest("生成结果状态无效");
        }
        if (generations.transition(tenant, id, attempt, version, status, error) != 1) {
            throw BusinessException.conflict("生成任务状态已变化");
        }
        audit(t, null, "document:generate:result");
        return true;
    }

    private void authorize(Identity actor, long caseId, boolean lock) {
        cases.require(actor, "task:read");
        cases.authorize(actor, caseId, Action.UPDATE, "ai:generate", lock);
        cases.authorize(actor, caseId, Action.UPDATE, "document:write", false);
        cases.authorize(actor, caseId, Action.READ, "document:read", false);
    }

    private Identity creator(AsyncTaskModel t) {
        var u = users.selectById(t.getCreatedBy());
        if (u == null) {
            throw new AccessDenied();
        }
        return new Identity(u.getId(), t.getTenantId(), u.getSessionVersion(), u.getDisplayName());
    }

    private AsyncTaskModel task(long tenant, long id, boolean lock) {
        var t = lock
            ? tasks.selectByIdForUpdate(tenant, id)
            : tasks.selectOne(
                  new LambdaQueryWrapper<AsyncTaskModel>()
                      .eq(AsyncTaskModel::getTenantId, tenant)
                      .eq(AsyncTaskModel::getId, id)
              );
        if (t == null || !"DOCUMENT_GENERATE".equals(t.getType())) {
            throw new AccessDenied();
        }
        return t;
    }

    private DocumentGenerationRepository.Generation generation(AsyncTaskModel t) {
        var g = generations.get(t.getTenantId(), t.getId());
        if (g == null) {
            throw BusinessException.notFound("生成输入不存在");
        }
        return g;
    }

    private void execution(AsyncTaskModel t, int attempt, int version, boolean firstReplay) {
        // 首次投递可能已将 QUEUED 推进一版到 RUNNING；允许该同批次重放，其余旧版本均拒绝。
        boolean replay =
            firstReplay &&
            version >= 1 &&
            version < Integer.MAX_VALUE &&
            t.getStateVersion() == version + 1 &&
            "RUNNING".equals(t.getStatus());
        if (
            !"DOCUMENT_GENERATE".equals(t.getType()) ||
            t.getAttempt() != attempt ||
            (t.getStateVersion() != version && !replay) ||
            !Set.of("QUEUED", "RUNNING").contains(t.getStatus())
        ) {
            throw BusinessException.conflict("生成任务状态已变化");
        }
    }

    private Requested requested(AsyncTaskModel t) {
        return new Requested(t.getId().toString(), t.getStatus(), t.getStateVersion());
    }

    private String encode(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("生成数据无法序列化");
        }
    }

    private List<String> decodeMissing(String v) {
        if (v == null) {
            return List.of();
        }
        try {
            return json.readValue(
                v,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}
            );
        } catch (Exception e) {
            throw new IllegalStateException("生成结果损坏");
        }
    }

    private void event(AsyncTaskModel t) {
        String id = UUID.randomUUID().toString();
        var p = new LinkedHashMap<String, Object>();
        p.put("eventId", id);
        p.put("schemaVersion", "1.0");
        p.put("tenantId", t.getTenantId().toString());
        p.put("taskId", t.getId().toString());
        p.put("caseId", t.getCaseId().toString());
        p.put("resourceVersionId", t.getResourceVersionId().toString());
        p.put("attempt", t.getAttempt());
        p.put("stateVersion", t.getStateVersion());
        p.put("traceId", TraceContext.currentOrNew());
        p.put("occurredAt", Instant.now().toString());
        outbox.insert(
            OutboxEventModel.builder()
                .eventId(id)
                .tenantId(t.getTenantId())
                .taskId(t.getId())
                .routingKey("document.generate.requested.v1")
                .payload(encode(p))
                .status("PENDING")
                .attempt(0)
                .build()
        );
    }

    private void audit(AsyncTaskModel t, Long actor, String action) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(t.getTenantId())
                .actorId(actor)
                .action(action)
                .objectType("document_generation")
                .objectId(t.getId().toString())
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
