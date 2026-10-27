package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AiConfigurationMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.CaseAnalysisRepository;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.TaskResultService.SourceSegment;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import com.lexflow.service.vo.CaseAnalysisReport;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定选定材料与模型修订，由 Java 校验权限、真实引用和人工决定；AI 服务负责计算报告。 */
@Service
public class CaseAnalysisService {

    private final AsyncTaskMapper tasks;
    private final CaseAnalysisRepository analyses;
    private final CaseService cases;
    private final TaskService taskViews;
    private final AiConfigurationService configs;
    private final AiConfigurationMapper configRows;
    private final OutboxEventMapper outbox;
    private final AuditLogMapper audits;
    private final SysUserMapper users;
    private final ObjectMapper json;

    public CaseAnalysisService(
        AsyncTaskMapper tasks,
        CaseAnalysisRepository analyses,
        CaseService cases,
        TaskService taskViews,
        AiConfigurationService configs,
        AiConfigurationMapper configRows,
        OutboxEventMapper outbox,
        AuditLogMapper audits,
        SysUserMapper users,
        ObjectMapper json
    ) {
        this.tasks = tasks;
        this.analyses = analyses;
        this.cases = cases;
        this.taskViews = taskViews;
        this.configs = configs;
        this.configRows = configRows;
        this.outbox = outbox;
        this.audits = audits;
        this.users = users;
        this.json = json;
    }

    /** 提交时固定的材料快照；文件摘要、正文和页段位置须与后续读取到的来源完全一致。 */
    public record Source(
        String parseTaskId,
        String fileVersionId,
        String fileId,
        String fileName,
        int fileVersion,
        String sha256,
        String text,
        List<SourceSegment> segments
    ) {}

    public record Requested(String taskId, String status, int stateVersion) {}

    /**
     * waitingReason 区分人工复核 REVIEW、投递待处理 DELIVERY 与执行结果不确定 UNCERTAIN。
     * 三者都可能对应 WAITING_HUMAN，只有 REVIEW 可以提交人工确认决定。
     */
    public record View(
        String taskId,
        String caseId,
        String status,
        int stateVersion,
        String background,
        String requirements,
        List<Source> sources,
        CaseAnalysisReport report,
        String waitingReason,
        String decisionId,
        String decidedBy,
        String decisionNote
    ) {}

    /** 内部执行端的固定输入，包含运行时 API Key；toString 脱敏，不能直接作为外部响应。 */
    public record Input(
        String tenantId,
        String taskId,
        String caseId,
        int attempt,
        int stateVersion,
        String background,
        String requirements,
        List<Source> sources,
        String provider,
        String baseUrl,
        String model,
        String configurationRevision,
        String apiKey,
        String decisionId
    ) {
        @Override
        public String toString() {
            return "CaseAnalysisInput[redacted]";
        }
    }

    /** 恢复资格检查专用的固定输入，只包含模型元数据，不含运行时凭据。 */
    public record RecoveryInput(
        String tenantId,
        String taskId,
        String caseId,
        int attempt,
        int stateVersion,
        String background,
        String requirements,
        List<Source> sources,
        String provider,
        String baseUrl,
        String model,
        String configurationRevision,
        String decisionId
    ) {
        @Override
        public String toString() {
            return "CaseAnalysisRecoveryInput[redacted]";
        }
    }

    public List<Source> sources(Identity actor, long caseId) {
        authorize(actor, caseId, Action.READ, false);
        return analyses.candidates(actor.tenantId(), caseId).stream().map(this::source).toList();
    }

    /**
     * 确认费用后登记一次分析：固定排序后的材料快照、背景、要求和模型修订，并创建投递事件。
     * 同一请求标识仅可复用创建者及全部固定输入都一致的任务。
     */
    @Transactional
    public Requested request(
        Identity actor,
        long caseId,
        List<Long> parseTaskIds,
        String background,
        String requirements,
        String key,
        boolean confirmCosts
    ) {
        if (actor == null) {
            throw new AccessDenied();
        }
        if (!confirmCosts) {
            throw BusinessException.badRequest("请确认案件分析可能产生模型费用");
        }
        if (
            parseTaskIds == null ||
            parseTaskIds.isEmpty() ||
            parseTaskIds.size() > 10 ||
            parseTaskIds.stream().anyMatch(id -> id == null || id < 1) ||
            new HashSet<>(parseTaskIds).size() != parseTaskIds.size() ||
            background == null ||
            background.isBlank() ||
            background.length() > 3000 ||
            requirements == null ||
            requirements.isBlank() ||
            requirements.length() > 2000 ||
            key == null ||
            key.isBlank() ||
            key.length() > 128
        ) {
            throw BusinessException.badRequest(
                "请选择 1–10 份已解析材料，填写案件背景（最多3000字）和分析要求（最多2000字），并提供请求标识"
            );
        }
        background = background.trim();
        requirements = requirements.trim();
        key = key.trim();
        authorize(actor, caseId, Action.UPDATE, true);
        var ids = parseTaskIds.stream().sorted().toList();
        var sourceList = ids
            .stream()
            .map(id -> {
                var p = analyses.parsed(actor.tenantId(), caseId, id);
                if (p == null) {
                    throw new AccessDenied();
                }
                return source(p);
            })
            .toList();
        if (
            sourceList
                .stream()
                .mapToInt(s -> s.text().length())
                .sum() > 40000
        ) {
            throw BusinessException.badRequest("选定材料总文字超过4万字，请缩小本次材料范围");
        }
        String sources = encode(sourceList),
            model = modelIdentity(actor.tenantId());
        // 保存完整材料快照并按排序后的来源比较幂等请求，防止相同标识悄然换用其他材料。
        var existing = tasks.selectOne(
            new LambdaQueryWrapper<AsyncTaskModel>()
                .eq(AsyncTaskModel::getTenantId, actor.tenantId())
                .eq(AsyncTaskModel::getIdempotencyKey, key)
        );
        if (existing != null) {
            var old = analyses.get(actor.tenantId(), existing.getId());
            if (
                old == null ||
                !"CASE_ANALYSIS".equals(existing.getType()) ||
                !Objects.equals(existing.getCreatedBy(), actor.userId()) ||
                !Objects.equals(existing.getCaseId(), caseId) ||
                !old.background().equals(background) ||
                !old.requirements().equals(requirements) ||
                !readSources(old).equals(sourceList) ||
                !old.modelIdentity().equals(model)
            ) {
                throw BusinessException.conflict("请求标识已用于不同案件分析，请使用新标识");
            }
            return requested(existing);
        }
        var task = AsyncTaskModel.builder()
            .tenantId(actor.tenantId())
            .caseId(caseId)
            .type("CASE_ANALYSIS")
            .resourceVersionId(Long.parseLong(sourceList.getFirst().fileVersionId()))
            .status("QUEUED")
            .attempt(0)
            .stateVersion(1)
            .createdBy(actor.userId())
            .idempotencyKey(key)
            .build();
        try {
            tasks.insert(task);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("分析已同时提交，请刷新");
        }
        analyses.create(actor.tenantId(), task.getId(), background, requirements, model, sources);
        event(task);
        audit(task, actor.userId(), "case-analysis:submit");
        return requested(task);
    }

    /** 展示前同时复查查看者和原创建者的来源访问权，并核对已保存报告的真实引用。 */
    public View view(Identity actor, long id) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, false);
        var row = row(task);
        authorize(actor, task.getCaseId(), Action.READ, false);
        var sources = readSources(row);
        validateSources(actor, task, sources);
        // 复核者权限较高也不能继续交付已对原创建者失效的材料，双方来源访问均需有效。
        validateSources(creator(task), task, sources);
        var report =
            row.reportJson() == null ? null : decode(row.reportJson(), CaseAnalysisReport.class);
        if (report != null) {
            validateReport(report, sources);
        }
        return new View(
            task.getId().toString(),
            task.getCaseId().toString(),
            task.getStatus(),
            task.getStateVersion(),
            row.background(),
            row.requirements(),
            sources,
            report,
            row.waitingReason(),
            row.decisionId(),
            row.decidedBy() == null ? null : row.decidedBy().toString(),
            row.decisionNote()
        );
    }

    /** 校验执行轮次与来源；首次把 QUEUED 推进为 RUNNING，返回推进后的 stateVersion。 */
    @Transactional
    public Input begin(long id, int attempt, int version) {
        var task = tasks.selectLockById(id);
        if (task == null) {
            throw new AccessDenied();
        }
        execution(task, attempt, version);
        var row = row(task);
        requireNoDeliveryWait(task, row);
        validateExecution(task, row);
        if ("QUEUED".equals(task.getStatus())) {
            if (
                analyses.transition(task.getTenantId(), id, attempt, version, "RUNNING", null) != 1
            ) {
                throw BusinessException.conflict("分析状态已变化");
            }
            version++;
        }
        return input(id, attempt, version);
    }

    /** 每次供执行端读取时重新核对创建者、固定材料和模型修订，不沿用首次执行时的授权。 */
    public Input input(long id, int attempt, int version) {
        var task = tasks.selectById(id);
        if (task == null) {
            throw BusinessException.notFound("分析任务不存在");
        }
        execution(task, attempt, version);
        var row = row(task);
        requireNoDeliveryWait(task, row);
        validateExecution(task, row);
        var model = configs.llmForQuestion(task.getTenantId());
        return new Input(
            task.getTenantId().toString(),
            task.getId().toString(),
            task.getCaseId().toString(),
            attempt,
            version,
            row.background(),
            row.requirements(),
            readSources(row),
            model.provider(),
            model.baseUrl(),
            model.model(),
            revision(task.getTenantId()),
            model.apiKey(),
            row.decisionId()
        );
    }

    /** 仅检查死信恢复输入，不推进任务状态、清除执行记录或授权模型调用。 */
    @Transactional(noRollbackFor = { AccessDenied.class, BusinessException.class })
    public RecoveryInput recoveryInput(
        Identity actor,
        long id,
        int attempt,
        int registeredStateVersion
    ) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        var row = row(task);
        if (
            task.getAttempt() != attempt ||
            task.getStateVersion() != registeredStateVersion ||
            !"WAITING_HUMAN".equals(task.getStatus()) ||
            !Set.of("DELIVERY", "UNCERTAIN").contains(Objects.toString(row.waitingReason(), "")) ||
            !analyses.currentDeadDelivery(task.getTenantId(), id, attempt, registeredStateVersion)
        ) {
            throw BusinessException.conflict("案件分析投递记录已变化或无需恢复，请刷新");
        }
        return validatedRecoveryInput(actor, task, row);
    }

    /** 为幂等恢复响应重查固定输入；仅通过此检查不能证明任务可安全恢复。 */
    @Transactional(noRollbackFor = { AccessDenied.class, BusinessException.class })
    public RecoveryInput currentRecoveryInput(Identity actor, long id) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        return validatedRecoveryInput(actor, task, row(task));
    }

    /** 通用投递流程已锁定并按版本更新任务，此处只记录案件分析的等待原因。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDeliveryWaiting(AsyncTaskModel task, String kind) {
        if (task == null || !"CASE_ANALYSIS".equals(task.getType())) {
            throw BusinessException.conflict("此任务不是案件分析");
        }
        row(task);
        analyses.deliveryWaiting(
            task.getTenantId(),
            task.getId(),
            "UNCERTAIN".equals(kind) ? "UNCERTAIN" : "DELIVERY"
        );
    }

    /** 与安全投递恢复的状态更新同事务清除等待原因，保留已保存报告及人工决定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearDeliveryWaiting(AsyncTaskModel task) {
        if (task == null || !"CASE_ANALYSIS".equals(task.getType())) {
            throw BusinessException.conflict("此任务不是案件分析");
        }
        var row = row(task);
        if (row.executionProtocol() != 2 || !"DELIVERY".equals(row.waitingReason())) {
            throw BusinessException.conflict("案件分析投递状态不允许恢复");
        }
        analyses.clearDeliveryWaiting(task.getTenantId(), task.getId());
    }

    /**
     * 只确认待复核的有效报告；同一决定标识、操作者和意见可以幂等重交。
     * 决定保存后推进为 RUNNING 并重新投递，由执行端继续发布已确认报告。
     */
    @Transactional
    public Requested approve(Identity actor, long id, int version, String decisionId, String note) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        authorize(actor, task.getCaseId(), Action.UPDATE, true);
        var row = row(task);
        requireNoDeliveryWait(task, row);
        validateSources(actor, task, readSources(row));
        validateExecution(task, row);
        if (
            decisionId == null ||
            !decisionId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") ||
            note == null ||
            note.isBlank() ||
            note.length() > 500
        ) {
            throw BusinessException.badRequest("请填写人工复核意见及决定标识");
        }
        note = note.trim();
        if (row.decisionId() != null) {
            if (
                row.decisionId().equals(decisionId) &&
                Objects.equals(row.decidedBy(), actor.userId()) &&
                row.decisionNote().equals(note)
            ) {
                return requested(task);
            }
            throw BusinessException.conflict("此分析已有人工作出决定");
        }
        if (
            task.getStateVersion() != version ||
            !"WAITING_HUMAN".equals(task.getStatus()) ||
            !"REVIEW".equals(row.waitingReason()) ||
            row.reportJson() == null
        ) {
            throw BusinessException.conflict("此任务不可复核确认，不确定的模型执行需取消并核对");
        }
        validateReport(decode(row.reportJson(), CaseAnalysisReport.class), readSources(row));
        try {
            analyses.decision(actor.tenantId(), id, decisionId, actor.userId(), note);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("决定标识已用于另一任务");
        }
        if (
            analyses.transition(
                actor.tenantId(),
                id,
                task.getAttempt(),
                version,
                "RUNNING",
                null
            ) != 1
        ) {
            throw BusinessException.conflict("分析状态已变化");
        }
        task.setStatus("RUNNING");
        task.setStateVersion(version + 1);
        event(task);
        audit(task, actor.userId(), "case-analysis:approve");
        return requested(task);
    }

    /** 相同输入的重放沿用操作记账，重新投递本身不授权新的模型调用。 */
    @Transactional
    public Requested recover(Identity actor, long id, int version) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        authorize(actor, task.getCaseId(), Action.UPDATE, true);
        var row = row(task);
        requireNoDeliveryWait(task, row);
        validateSources(actor, task, readSources(row));
        validateExecution(task, row);
        if (
            task.getStateVersion() != version ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            throw BusinessException.conflict("仅可恢复排队或执行中的分析");
        }
        event(task);
        audit(task, actor.userId(), "case-analysis:recover");
        return requested(task);
    }

    /**
     * 只接受匹配当前 attempt、stateVersion 的执行回调，过期回调返回 false。
     * UNCERTAIN 不自动重试模型；有效报告可等待人工复核，已有人工决定后的报告不得改变。
     */
    @Transactional
    public boolean apply(
        long tenant,
        long id,
        int attempt,
        int version,
        String outcome,
        CaseAnalysisReport report
    ) {
        var task = tasks.selectByIdForUpdate(tenant, id);
        if (
            task == null ||
            !"CASE_ANALYSIS".equals(task.getType()) ||
            task.getAttempt() != attempt ||
            task.getStateVersion() != version ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            return false;
        }
        var row = row(task);
        String status,
            reason = null,
            error = null;
        if ("FAILED".equals(outcome)) {
            status = "FAILED";
            report = null;
            error = "案件分析未完成，请检查材料和模型服务";
        } else if ("UNCERTAIN".equals(outcome)) {
            status = "WAITING_HUMAN";
            reason = "UNCERTAIN";
            report = null;
            error = "模型执行结果不确定，不会自动再次调用；请取消并核对执行记录";
        } else {
            validateExecution(task, row);
            validateReport(report, readSources(row));
            if (
                "WAITING_HUMAN".equals(outcome) && report.needsHuman() && row.decisionId() == null
            ) {
                status = "WAITING_HUMAN";
                reason = "REVIEW";
            } else if (
                "SUCCEEDED".equals(outcome) && (!report.needsHuman() || row.decisionId() != null)
            ) {
                status = "SUCCEEDED";
            } else {
                throw BusinessException.conflict("分析结果与人工复核状态不一致");
            }
            if (
                row.decisionId() != null &&
                !Objects.equals(decode(row.reportJson(), CaseAnalysisReport.class), report)
            ) {
                throw BusinessException.conflict("复核后的分析报告不可更改");
            }
        }
        if (analyses.transition(tenant, id, attempt, version, status, error) != 1) {
            return false;
        }
        analyses.result(tenant, id, report == null ? null : encode(report), reason);
        audit(task, null, "case-analysis:result");
        return true;
    }

    private Source source(CaseAnalysisRepository.Parsed p) {
        if (
            p.text() == null ||
            p.text().isBlank() ||
            p.text().length() > 40000 ||
            p.segmentsJson() == null
        ) {
            throw BusinessException.badRequest("材料无页段定位、为空或超过4万字，请重新解析或拆分");
        }
        List<SourceSegment> segments;
        try {
            segments = json.readValue(
                p.segmentsJson(),
                new TypeReference<List<SourceSegment>>() {}
            );
        } catch (Exception e) {
            throw new IllegalStateException("材料定位损坏");
        }
        if (segments.isEmpty() || segments.size() > 5000) {
            throw BusinessException.badRequest("材料定位无效，请重新解析");
        }
        int end = 0,
            index = 0;
        for (var segment : segments) {
            if (
                segment == null ||
                !Set.of("PAGE", "PARAGRAPH").contains(segment.kind()) ||
                segment.index() <= index ||
                segment.startOffset() < end ||
                segment.endOffset() <= segment.startOffset() ||
                segment.endOffset() > p.text().length()
            ) {
                throw BusinessException.badRequest("材料定位与原文不符");
            }
            end = segment.endOffset();
            index = segment.index();
        }
        return new Source(
            Long.toString(p.taskId()),
            Long.toString(p.versionId()),
            Long.toString(p.fileId()),
            p.name(),
            p.fileVersion(),
            p.sha256(),
            p.text(),
            segments
        );
    }

    /** 执行资格属于原创建者；管理员恢复或复核任务不能替代创建者失效的权限。 */
    private void validateExecution(AsyncTaskModel task, CaseAnalysisRepository.Analysis row) {
        var actor = creator(task);
        authorize(actor, task.getCaseId(), Action.UPDATE, false);
        validateSources(actor, task, readSources(row));
        if (!row.modelIdentity().equals(modelIdentity(task.getTenantId()))) {
            throw BusinessException.conflict("分析模型配置已变化，请重新提交");
        }
    }

    private void validateSources(Identity actor, AsyncTaskModel task, List<Source> sources) {
        validateSources(actor, task, sources, false);
    }

    /** 逐份比对保存快照与当前解析来源；恢复检查可加共享锁，防止检查期间来源发生变化。 */
    private void validateSources(
        Identity actor,
        AsyncTaskModel task,
        List<Source> sources,
        boolean lock
    ) {
        authorize(actor, task.getCaseId(), Action.READ, false);
        for (var saved : sources) {
            var live = analyses.parsed(
                task.getTenantId(),
                task.getCaseId(),
                Long.parseLong(saved.parseTaskId()),
                lock
            );
            if (live == null || !saved.equals(source(live))) {
                throw new AccessDenied();
            }
        }
    }

    /**
     * 同时验证恢复操作者、创建者、材料、既有报告和模型修订。
     * executionProtocol=2 是已采用模型执行记账协议的前提，资格输入不包含运行时凭据。
     */
    private RecoveryInput validatedRecoveryInput(
        Identity actor,
        AsyncTaskModel task,
        CaseAnalysisRepository.Analysis row
    ) {
        cases.require(actor, "task:cancel");
        authorize(actor, task.getCaseId(), Action.UPDATE, true);
        if (row.executionProtocol() != 2) {
            throw BusinessException.conflict(
                "历史案件分析无法证明模型执行已记账，请取消并核对后重新提交"
            );
        }
        var sources = readSources(row);
        if (
            sources == null ||
            sources.isEmpty() ||
            sources.size() > 10 ||
            sources.stream().anyMatch(Objects::isNull) ||
            new HashSet<>(sources.stream().map(Source::parseTaskId).toList()).size() !=
                sources.size() ||
            sources
                .stream()
                .mapToInt(s -> s.text() == null ? 40001 : s.text().length())
                .sum() > 40000 ||
            !task.getResourceVersionId().toString().equals(sources.getFirst().fileVersionId())
        ) {
            throw BusinessException.conflict("案件分析固定材料身份无效，请取消并核对");
        }
        validateSources(actor, task, sources, true);
        var creator = creator(task);
        authorize(creator, task.getCaseId(), Action.UPDATE, false);
        validateSources(creator, task, sources, true);
        if (row.reportJson() != null) {
            validateReport(decode(row.reportJson(), CaseAnalysisReport.class), sources);
        }
        // 资格检查直接锁定配置元数据，不解密或转发 API Key。
        var model = configRows.lockInternal(task.getTenantId(), "LLM");
        if (model == null || model.get("revision") == null) {
            throw BusinessException.conflict("模型配置已删除，请重新提交分析");
        }
        String provider = (String) model.get("provider"),
            baseUrl = (String) model.get("baseUrl"),
            name = (String) model.get("model"),
            revision = model.get("revision").toString();
        if (
            !row
                .modelIdentity()
                .equals(encode(List.of(provider.trim(), baseUrl.trim(), name.trim(), revision)))
        ) {
            throw BusinessException.conflict("分析模型配置已变化，请重新提交");
        }
        return new RecoveryInput(
            task.getTenantId().toString(),
            task.getId().toString(),
            task.getCaseId().toString(),
            task.getAttempt(),
            task.getStateVersion(),
            row.background(),
            row.requirements(),
            sources,
            provider,
            baseUrl,
            name,
            revision,
            row.decisionId()
        );
    }

    /** 投递待处理和不确定执行必须先走安全恢复流程，不能借开始执行、复核或普通重放绕过。 */
    private void requireNoDeliveryWait(AsyncTaskModel task, CaseAnalysisRepository.Analysis row) {
        if (
            Set.of("DELIVERY", "UNCERTAIN").contains(Objects.toString(row.waitingReason(), "")) ||
            analyses.currentDeadDelivery(
                task.getTenantId(),
                task.getId(),
                task.getAttempt(),
                task.getStateVersion()
            )
        ) {
            throw BusinessException.conflict(
                "投递待处理或执行结果不确定，请通过处理记录核对后恢复或取消"
            );
        }
    }

    private void validateReport(CaseAnalysisReport report, List<Source> sources) {
        // 模型引用必须落在提交时选定材料的真实页段内，正文与字符区间须完全一致。
        if (report == null) {
            throw BusinessException.badRequest("案件分析报告缺失");
        }
        report.validate();
        var selected = new HashMap<String, Source>();
        for (var s : sources) {
            selected.put(s.parseTaskId(), s);
        }
        for (var c : report.citations()) {
            var s = selected.get(c.parseTaskId());
            if (
                s == null ||
                !s.fileVersionId().equals(c.fileVersionId()) ||
                !s.sha256().equals(c.sha256()) ||
                c.endOffset() > s.text().length() ||
                !s.text().substring(c.startOffset(), c.endOffset()).equals(c.quote()) ||
                s
                    .segments()
                    .stream()
                    .noneMatch(
                        seg ->
                            seg.kind().equals(c.kind()) &&
                            seg.index() == c.index() &&
                            seg.startOffset() <= c.startOffset() &&
                            seg.endOffset() >= c.endOffset()
                    )
            ) {
                throw BusinessException.badRequest("分析引用不在选定材料的真实页段中");
            }
        }
    }

    private void authorize(Identity actor, long caseId, Action action, boolean lock) {
        // 任务查询、AI 审查与原件读取是三层独立权限，案件角色检查不能替代其中任意一层。
        cases.require(actor, "task:read");
        cases.authorize(actor, caseId, action, "ai:review", lock);
        cases.authorize(actor, caseId, Action.READ, "file:read", false);
    }

    private Identity creator(AsyncTaskModel task) {
        var u = users.selectById(task.getCreatedBy());
        if (u == null) {
            throw new AccessDenied();
        }
        return new Identity(
            u.getId(),
            task.getTenantId(),
            u.getSessionVersion(),
            u.getDisplayName()
        );
    }

    private void execution(AsyncTaskModel task, int attempt, int version) {
        if (
            !"CASE_ANALYSIS".equals(task.getType()) ||
            task.getAttempt() != attempt ||
            task.getStateVersion() != version ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            throw BusinessException.conflict("案件分析状态已变化");
        }
    }

    private AsyncTaskModel task(long tenant, long id, boolean lock) {
        var t = lock
            ? tasks.selectByIdForUpdate(tenant, id)
            : tasks.selectOne(
                  new LambdaQueryWrapper<AsyncTaskModel>()
                      .eq(AsyncTaskModel::getTenantId, tenant)
                      .eq(AsyncTaskModel::getId, id)
              );
        if (t == null || !"CASE_ANALYSIS".equals(t.getType())) {
            throw new AccessDenied();
        }
        return t;
    }

    private CaseAnalysisRepository.Analysis row(AsyncTaskModel task) {
        var r = analyses.get(task.getTenantId(), task.getId());
        if (r == null) {
            throw BusinessException.notFound("案件分析输入不存在");
        }
        return r;
    }

    private List<Source> readSources(CaseAnalysisRepository.Analysis row) {
        try {
            return json.readValue(row.sourcesJson(), new TypeReference<List<Source>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("案件分析输入损坏");
        }
    }

    private String modelIdentity(long tenant) {
        var m = configs.llmForQuestion(tenant);
        return encode(
            List.of(m.provider().trim(), m.baseUrl().trim(), m.model().trim(), revision(tenant))
        );
    }

    private String revision(long tenant) {
        var r = configRows.internal(tenant, "LLM");
        if (r == null || r.get("revision") == null) {
            throw BusinessException.conflict("模型配置未提供修订标识，请重新保存配置");
        }
        return r.get("revision").toString();
    }

    private Requested requested(AsyncTaskModel task) {
        return new Requested(task.getId().toString(), task.getStatus(), task.getStateVersion());
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("分析报告损坏");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("分析数据无法序列化");
        }
    }

    private void event(AsyncTaskModel task) {
        String id = UUID.randomUUID().toString();
        var p = new LinkedHashMap<String, Object>();
        p.put("eventId", id);
        p.put("schemaVersion", "1.0");
        p.put("tenantId", task.getTenantId().toString());
        p.put("taskId", task.getId().toString());
        p.put("caseId", task.getCaseId().toString());
        p.put("type", "CASE_ANALYSIS");
        p.put("attempt", task.getAttempt());
        p.put("stateVersion", task.getStateVersion());
        p.put("resourceVersionId", task.getResourceVersionId().toString());
        p.put("traceId", TraceContext.currentOrNew());
        p.put("occurredAt", Instant.now().toString());
        outbox.insert(
            OutboxEventModel.builder()
                .eventId(id)
                .tenantId(task.getTenantId())
                .taskId(task.getId())
                .routingKey("case.analysis.requested.v1")
                .payload(encode(p))
                .status("PENDING")
                .attempt(0)
                .build()
        );
    }

    private void audit(AsyncTaskModel task, Long actor, String action) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(task.getTenantId())
                .actorId(actor)
                .action(action)
                .objectType("case_analysis")
                .objectId(task.getId().toString())
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
