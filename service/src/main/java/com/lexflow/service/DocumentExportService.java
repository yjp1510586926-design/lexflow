package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.DocumentExportRepository;
import com.lexflow.dao.DocumentExportRepository.*;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.file.LifecyclePolicy;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Java 管理固定正文版本的 TXT 导出；每次执行使用独立对象键和租约，成功发布后才可下载。 */
@Service
public class DocumentExportService {

    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private final DocumentExportRepository jobs;
    private final AsyncTaskMapper tasks;
    private final CaseService cases;
    private final TaskService taskViews;
    private final SysUserMapper users;
    private final OutboxEventMapper outbox;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final LifecyclePolicy lifecycle;

    public DocumentExportService(
        DocumentExportRepository jobs,
        AsyncTaskMapper tasks,
        CaseService cases,
        TaskService taskViews,
        SysUserMapper users,
        OutboxEventMapper outbox,
        AuditLogMapper audits,
        ObjectMapper json,
        LifecyclePolicy lifecycle
    ) {
        this.jobs = jobs;
        this.tasks = tasks;
        this.cases = cases;
        this.taskViews = taskViews;
        this.users = users;
        this.outbox = outbox;
        this.audits = audits;
        this.json = json;
        this.lifecycle = lifecycle;
    }

    public record View(
        String taskId,
        String status,
        int stateVersion,
        int attempt,
        String documentId,
        String versionId,
        int versionNo,
        String title,
        String documentStatus,
        String errorMessage,
        String expiresAt,
        boolean expired
    ) {}

    public record Claim(
        long tenant,
        long task,
        int attempt,
        int stateVersion,
        String token,
        String objectKey,
        byte[] bytes
    ) {}

    public record Download(
        String key,
        String title,
        int versionNo,
        String status,
        String sha256,
        long size
    ) {}

    @Transactional
    public View request(
        Identity actor,
        long caseId,
        long document,
        long expectedVersion,
        String key
    ) {
        if (key == null || key.isBlank() || key.length() > 128 || expectedVersion <= 0) {
            throw BusinessException.badRequest("请选择已保存版本并提供请求标识");
        }
        key = key.trim();
        cases.require(actor, "task:read");
        cases.authorize(actor, caseId, Action.READ, "document:read", true);
        var existing = tasks.selectOne(
            new LambdaQueryWrapper<AsyncTaskModel>()
                .eq(AsyncTaskModel::getTenantId, actor.tenantId())
                .eq(AsyncTaskModel::getIdempotencyKey, key)
        );
        if (existing != null) {
            var job = jobs.get(actor.tenantId(), existing.getId());
            if (
                job == null ||
                !"EXPORT".equals(existing.getType()) ||
                !Objects.equals(existing.getCreatedBy(), actor.userId()) ||
                !Objects.equals(existing.getCaseId(), caseId) ||
                job.document() != document ||
                job.version() != expectedVersion
            ) {
                throw BusinessException.conflict("请求标识已用于不同导出，请使用新的标识");
            }
            source(actor, existing, job);
            return view(existing, job);
        }
        var source = jobs.source(actor.tenantId(), caseId, document, null);
        if (source == null) {
            throw new AccessDenied();
        }
        if (source.version() != expectedVersion) {
            throw BusinessException.conflict("文书当前版本已变化，请刷新后再导出");
        }
        // 保存来源正文摘要与大小，执行时继续校验该版本，不能偷偷导出后续编辑的内容。
        byte[] bytes = bytes(source);
        var task = AsyncTaskModel.builder()
            .tenantId(actor.tenantId())
            .caseId(caseId)
            .type("EXPORT")
            .resourceVersionId(source.version())
            .status("QUEUED")
            .attempt(0)
            .stateVersion(1)
            .createdBy(actor.userId())
            .idempotencyKey(key)
            .build();
        try {
            tasks.insert(task);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("导出已同时提交，请刷新后重试");
        }
        jobs.create(
            actor.tenantId(),
            task.getId(),
            document,
            source,
            sha256(bytes),
            bytes.length,
            lifecycle.exportRetentionDays()
        );
        event(task, 0);
        audit(task, actor.userId(), "document:export:submit");
        return view(task, jobs.get(actor.tenantId(), task.getId()));
    }

    @Transactional
    public View view(Identity actor, long id) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id);
        var job = job(task);
        source(actor, task, job);
        return view(task, job);
    }

    @Transactional
    public View recover(Identity actor, long id, int version) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id);
        var job = job(task);
        source(actor, task, job);
        if (!"QUEUED".equals(task.getStatus()) || task.getStateVersion() != version) {
            throw BusinessException.conflict("此任务不能重新投递，请刷新后查看");
        }
        event(task, 0);
        audit(task, actor.userId(), "document:export:recover");
        return view(task, job);
    }

    @Transactional
    public Claim begin(long tenant, long id, int attempt, int version) {
        var task = task(tenant, id);
        if (
            task == null ||
            task.getAttempt() != attempt ||
            task.getStateVersion() != version ||
            !"QUEUED".equals(task.getStatus())
        ) {
            return null;
        }
        var job = job(task);
        var actor = creator(task);
        cases.require(actor, "task:read");
        byte[] bytes = source(actor, task, job);
        String token = UUID.randomUUID().toString(),
            key = "tenants/" + tenant + "/exports/" + id + "/attempts/" + token + ".txt";
        // 租约登记与 RUNNING 状态同事务提交；每次执行有独立对象键，迟到写入不能覆盖新产物。
        if (jobs.transition(tenant, id, attempt, version, "RUNNING", null, null) != 1) {
            return null;
        }
        jobs.claim(tenant, id, token, key);
        return new Claim(tenant, id, attempt, version + 1, token, key, bytes);
    }

    @Transactional
    public boolean complete(Claim claim) {
        var task = task(claim.tenant(), claim.task());
        if (!owns(task, claim)) {
            return false;
        }
        // 对象写入已在事务外完成；发布前复查租约、创建者权限与原正文摘要。
        var job = job(task);
        var actor = creator(task);
        cases.require(actor, "task:read");
        source(actor, task, job);
        if (
            jobs.transition(
                claim.tenant(),
                claim.task(),
                claim.attempt(),
                claim.stateVersion(),
                "SUCCEEDED",
                null,
                claim.objectKey()
            ) != 1
        ) {
            return false;
        }
        jobs.publish(claim.tenant(), claim.task(), claim.objectKey());
        audit(task, null, "document:export:ready");
        return true;
    }

    @Transactional
    public void failQueued(long tenant, long id, int attempt, int version) {
        var task = task(tenant, id);
        if (
            task == null ||
            task.getAttempt() != attempt ||
            task.getStateVersion() != version ||
            !"QUEUED".equals(task.getStatus())
        ) {
            return;
        }
        job(task);
        jobs.transition(
            tenant,
            id,
            attempt,
            version,
            "FAILED",
            "导出材料或权限已变化，请返回文书检查",
            null
        );
        audit(task, null, "document:export:failed");
    }

    @Transactional
    public void fail(Claim claim, boolean retry) {
        var task = task(claim.tenant(), claim.task());
        if (!owns(task, claim)) {
            return;
        }
        retryOrFail(task, retry);
    }

    @Transactional
    public void recoverExpired(long tenant, long id) {
        var task = task(tenant, id);
        if (task == null || !"RUNNING".equals(task.getStatus()) || !job(task).leaseExpired()) {
            return;
        }
        retryOrFail(task, true);
    }

    private void retryOrFail(AsyncTaskModel task, boolean retry) {
        int a = task.getAttempt(),
            v = task.getStateVersion();
        // TXT 可从同一固定正文重建，重试时推进执行批次并释放旧租约，旧产物留待清理。
        if (retry && a < 3) {
            if (jobs.requeue(task.getTenantId(), task.getId(), a, v) != 1) {
                return;
            }
            jobs.release(task.getTenantId(), task.getId());
            task.setStatus("QUEUED");
            task.setAttempt(a + 1);
            task.setStateVersion(v + 1);
            event(task, new int[] { 10, 60, 300 }[a]);
            audit(task, null, "document:export:retry");
        } else {
            jobs.transition(
                task.getTenantId(),
                task.getId(),
                a,
                v,
                "FAILED",
                retry ? "文件生成失败，请返回文书重新导出" : "导出材料或权限已变化，请返回文书检查",
                null
            );
            jobs.release(task.getTenantId(), task.getId());
            audit(task, null, "document:export:failed");
        }
    }

    @Transactional
    public Download download(Identity actor, long id) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id);
        var job = job(task);
        source(actor, task, job);
        if (job.expired()) {
            throw BusinessException.conflict("导出文件已到期，请返回文书重新导出");
        }
        if (!"SUCCEEDED".equals(task.getStatus()) || job.objectKey() == null) {
            throw BusinessException.conflict("导出文件尚未准备完成");
        }
        return new Download(
            job.objectKey(),
            job.title(),
            job.versionNo(),
            job.status(),
            job.sha256(),
            job.size()
        );
    }

    @Transactional
    public void auditDownload(Identity actor, long id, Download downloaded) {
        var current = download(actor, id);
        if (!current.equals(downloaded)) {
            throw BusinessException.conflict("导出结果已变化，请刷新");
        }
        audit(task(actor.tenantId(), id), actor.userId(), "document:export:download");
    }

    @Transactional
    public boolean expireResult(long tenant, long id) {
        var task = task(tenant, id);
        if (task == null || !"SUCCEEDED".equals(task.getStatus()) || !job(task).expired()) {
            return false;
        }
        if (!jobs.expireResult(tenant, id)) {
            return false;
        }
        audit(task, null, "document:export:expired");
        return true;
    }

    @Transactional
    public boolean reserveCleanup(Artifact artifact) {
        var task = task(artifact.tenant(), artifact.task());
        if (task == null) {
            return false;
        }
        var job = job(task);
        // 当前发布结果及未过期的执行租约不可清理；对象键还须匹配已登记的归属记录。
        if (
            artifact.objectKey().equals(job.objectKey()) ||
            ("RUNNING".equals(task.getStatus()) &&
                artifact.token().equals(job.token()) &&
                !job.leaseExpired())
        ) {
            return false;
        }
        if (
            !Objects.equals(
                jobs.objectKey(artifact.token(), artifact.tenant(), artifact.task()),
                artifact.objectKey()
            )
        ) {
            return false;
        }
        return jobs.reserveCleanup(artifact);
    }

    private boolean owns(AsyncTaskModel task, Claim claim) {
        if (
            task == null ||
            !"RUNNING".equals(task.getStatus()) ||
            task.getAttempt() != claim.attempt() ||
            task.getStateVersion() != claim.stateVersion()
        ) {
            return false;
        }
        var job = job(task);
        return (
            claim.token().equals(job.token()) &&
            !job.leaseExpired() &&
            claim.objectKey().equals(jobs.objectKey(claim.token(), claim.tenant(), claim.task()))
        );
    }

    private AsyncTaskModel task(long tenant, long id) {
        var task = tasks.selectByIdForUpdate(tenant, id);
        if (task != null && !"EXPORT".equals(task.getType())) {
            throw new AccessDenied();
        }
        return task;
    }

    private Job job(AsyncTaskModel task) {
        if (task == null) {
            throw new AccessDenied();
        }
        var job = jobs.get(task.getTenantId(), task.getId());
        if (job == null) {
            throw new AccessDenied();
        }
        return job;
    }

    private byte[] source(Identity actor, AsyncTaskModel task, Job job) {
        cases.authorize(actor, task.getCaseId(), Action.READ, "document:read", true);
        var source = jobs.source(
            task.getTenantId(),
            task.getCaseId(),
            job.document(),
            job.version()
        );
        if (source == null) {
            throw new AccessDenied();
        }
        byte[] bytes = bytes(source);
        if (bytes.length != job.size() || !sha256(bytes).equals(job.sha256())) {
            throw BusinessException.conflict("已保存的来源版本不再匹配，请重新导出");
        }
        return bytes;
    }

    private Identity creator(AsyncTaskModel task) {
        var user = users.selectById(task.getCreatedBy());
        if (user == null) {
            throw new AccessDenied();
        }
        return new Identity(
            user.getId(),
            task.getTenantId(),
            user.getSessionVersion(),
            user.getDisplayName()
        );
    }

    public static byte[] bytes(Source source) {
        if ("AI_DRAFT".equals(source.source()) && !"APPROVED".equals(source.reviewStatus())) {
            throw BusinessException.conflict("AI 草稿须先通过人工复核，再导出");
        }
        byte[] bytes = (source.text() == null ? "" : source.text()).getBytes(
            StandardCharsets.UTF_8
        );
        if (bytes.length > MAX_BYTES) {
            throw BusinessException.badRequest("TXT 导出正文不能超过 8 MB");
        }
        return bytes;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private View view(AsyncTaskModel task, Job job) {
        return new View(
            task.getId().toString(),
            task.getStatus(),
            task.getStateVersion(),
            task.getAttempt(),
            Long.toString(job.document()),
            Long.toString(job.version()),
            job.versionNo(),
            job.title(),
            job.status(),
            task.getErrorMessage(),
            job.expiresAt(),
            job.expired()
        );
    }

    private void event(AsyncTaskModel task, int delay) {
        String id = UUID.randomUUID().toString();
        String payload;
        try {
            payload = json.writeValueAsString(
                Map.ofEntries(
                    Map.entry("eventId", id),
                    Map.entry("schemaVersion", "1.0"),
                    Map.entry("tenantId", task.getTenantId().toString()),
                    Map.entry("taskId", task.getId().toString()),
                    Map.entry("caseId", task.getCaseId().toString()),
                    Map.entry("resourceVersionId", task.getResourceVersionId().toString()),
                    Map.entry("type", "EXPORT"),
                    Map.entry("attempt", task.getAttempt()),
                    Map.entry("stateVersion", task.getStateVersion()),
                    Map.entry("traceId", TraceContext.currentOrNew()),
                    Map.entry("occurredAt", Instant.now().toString()),
                    Map.entry("payload", Map.of())
                )
            );
        } catch (Exception e) {
            throw new IllegalStateException("导出事件无法序列化", e);
        }
        outbox.insert(
            OutboxEventModel.builder()
                .eventId(id)
                .tenantId(task.getTenantId())
                .taskId(task.getId())
                .routingKey("document.export.requested.v1")
                .payload(payload)
                .status("PENDING")
                .attempt(0)
                .build()
        );
        if (delay > 0) {
            outbox.scheduleRetry(id, 0, delay);
        }
    }

    private void audit(AsyncTaskModel task, Long actor, String action) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(task.getTenantId())
                .actorId(actor)
                .action(action)
                .objectType("task")
                .objectId(task.getId().toString())
                .result(action.endsWith("failed") ? "FAILED" : "SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
