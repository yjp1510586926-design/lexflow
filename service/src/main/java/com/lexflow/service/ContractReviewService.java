package com.lexflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.ContractReviewRepository;
import com.lexflow.dao.FileVersionMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.FileVersionModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.FileService.DownloadResult;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import com.lexflow.service.vo.ContractReport;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定合同来源与审查输入，由 Java 校验当前权限、报告依据和人工决定后接受结果。 */
@Service
public class ContractReviewService {

    private final AsyncTaskMapper tasks;
    private final ContractReviewRepository reviews;
    private final CaseService cases;
    private final TaskService taskViews;
    private final AiConfigurationService configs;
    private final KnowledgeRepository knowledge;
    private final KnowledgeAccessService access;
    private final OutboxEventMapper outbox;
    private final AuditLogMapper audits;
    private final SysUserMapper users;
    private final ObjectMapper json;
    private final FileVersionMapper versions;
    private final PrivateObjectStorage storage;

    public ContractReviewService(
        AsyncTaskMapper tasks,
        ContractReviewRepository reviews,
        CaseService cases,
        TaskService taskViews,
        AiConfigurationService configs,
        KnowledgeRepository knowledge,
        KnowledgeAccessService access,
        OutboxEventMapper outbox,
        AuditLogMapper audits,
        SysUserMapper users,
        ObjectMapper json,
        FileVersionMapper versions,
        PrivateObjectStorage storage
    ) {
        this.tasks = tasks;
        this.reviews = reviews;
        this.cases = cases;
        this.taskViews = taskViews;
        this.configs = configs;
        this.knowledge = knowledge;
        this.access = access;
        this.outbox = outbox;
        this.audits = audits;
        this.users = users;
        this.json = json;
        this.versions = versions;
        this.storage = storage;
    }

    public record Requested(String taskId, String status, int stateVersion) {}

    /** waitingReason 区分待复核、投递问题和不确定执行；round 表示已登记的补材续审轮次。 */
    public record View(
        String taskId,
        String status,
        int stateVersion,
        String stance,
        String focus,
        ContractReport report,
        String waitingReason,
        String decisionId,
        String decidedBy,
        String decisionNote,
        int round,
        List<Supplement> supplements
    ) {}

    /** 页段定位沿用解析正文的 UTF-16 偏移，区间左闭右开；用于核对补材引用的真实位置。 */
    public record Segment(String kind, int index, int startOffset, int endOffset) {}

    /** 固定的补材版本、摘要和解析快照，续审时不能换成同一文件的其他版本。 */
    public record Supplement(
        String parseTaskId,
        String fileVersionId,
        String sha256,
        String fileName,
        String text,
        List<Segment> segments
    ) {}

    /** 从当前有效知识切片重新取得的引用元数据，可回到具体文件版本和页段。 */
    public record Citation(
        String id,
        String text,
        String scope,
        String knowledgeBaseId,
        String fileId,
        String fileVersionId,
        String fileName,
        int fileVersion,
        String locatorKind,
        int locatorIndex,
        int startOffset,
        int endOffset
    ) {}

    /**
     * 内部执行输入；补材轮次携带前一报告与累计材料，原合同来源保持固定。
     * apiKey 仅在正常执行输入中携带，恢复资格检查返回 null，toString 始终脱敏。
     */
    public record Input(
        String tenantId,
        String taskId,
        String fileVersionId,
        String sha256,
        String mimeType,
        int attempt,
        int stateVersion,
        String stance,
        String focus,
        String knowledgeBaseId,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        String decisionId,
        int round,
        String instructions,
        List<Supplement> supplements,
        ContractReport previousReport
    ) {
        @Override
        public String toString() {
            return "ContractReviewInput[redacted]";
        }
    }

    /** 固定合同版本、立场、关注点、参考库与模型身份；相同请求标识仅复用完全相同的输入。 */
    @Transactional
    public Requested request(
        Identity actor,
        long caseId,
        long version,
        String stance,
        String focus,
        Long baseId,
        String key
    ) {
        if (actor == null) {
            throw new AccessDenied();
        }
        if (
            stance == null ||
            !Set.of("PARTY_A", "PARTY_B", "NEUTRAL").contains(stance) ||
            focus == null ||
            focus.length() > 500 ||
            key == null ||
            key.length() > 128 ||
            key.isBlank()
        ) {
            throw BusinessException.badRequest("请选择审查立场，关注点最多 500 字，并提供请求标识");
        }
        focus = focus.trim();
        key = key.trim();
        cases.require(actor, "task:read");
        cases.authorize(actor, caseId, Action.UPDATE, "ai:review", true);
        cases.authorize(actor, caseId, Action.READ, "file:read", false);
        if (tasks.countAvailableSource(actor.tenantId(), caseId, version) != 1) {
            throw new AccessDenied();
        }
        if (baseId != null) {
            var base = reference(actor, caseId, baseId);
            var sources = knowledge.searchSources(actor.tenantId(), baseId, base.caseId());
            if (!sources.isEmpty()) {
                String embedding = modelIdentity(configs.embeddingForTask(actor.tenantId()));
                if (sources.stream().noneMatch(s -> embedding.equals(s.modelIdentity()))) {
                    throw BusinessException.conflict("参考资料索引模型已变化，请先重建索引");
                }
            }
        }
        var model = configs.llmForQuestion(actor.tenantId());
        String identity = modelIdentity(model);
        var existing = tasks.selectOne(
            new LambdaQueryWrapper<AsyncTaskModel>()
                .eq(AsyncTaskModel::getTenantId, actor.tenantId())
                .eq(AsyncTaskModel::getIdempotencyKey, key)
        );
        if (existing != null) {
            var r = reviews.get(actor.tenantId(), existing.getId());
            if (
                r == null ||
                !"CONTRACT_REVIEW".equals(existing.getType()) ||
                !Objects.equals(existing.getCreatedBy(), actor.userId()) ||
                !Objects.equals(existing.getCaseId(), caseId) ||
                !Objects.equals(existing.getResourceVersionId(), version) ||
                !r.stance().equals(stance) ||
                !r.focus().equals(focus) ||
                !Objects.equals(r.baseId(), baseId) ||
                !r.modelIdentity().equals(identity)
            ) {
                throw BusinessException.conflict("请求标识已用于不同审查，请使用新的标识");
            }
            return requested(existing);
        }
        var task = AsyncTaskModel.builder()
            .tenantId(actor.tenantId())
            .caseId(caseId)
            .type("CONTRACT_REVIEW")
            .resourceVersionId(version)
            .status("QUEUED")
            .attempt(0)
            .stateVersion(1)
            .createdBy(actor.userId())
            .idempotencyKey(key)
            .build();
        try {
            tasks.insert(task);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("请求已同时提交，请刷新后重试");
        }
        reviews.create(actor.tenantId(), task.getId(), stance, focus, baseId, identity);
        event(task);
        audit(task, actor.userId(), "contract:submit");
        return requested(task);
    }

    /** 交付报告前按当前权限核对参考库、报告引用和累计补材，失效来源不作为历史内容继续展示。 */
    public View view(Identity actor, long id) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, false);
        authorize(actor, task, Action.READ, false);
        var r = review(task);
        reference(actor, task.getCaseId(), r.baseId());
        ContractReport report = null;
        if (
            Set.of("WAITING_HUMAN", "RUNNING", "SUCCEEDED").contains(task.getStatus()) &&
            r.reportJson() != null
        ) {
            report = decode(r.reportJson());
            validateEvidence(actor, task, r, report);
        }
        var materials = supplements(actor, task);
        return new View(
            Long.toString(id),
            task.getStatus(),
            task.getStateVersion(),
            r.stance(),
            r.focus(),
            report,
            r.waitingReason(),
            r.decisionId(),
            r.decidedBy() == null ? null : r.decidedBy().toString(),
            r.decisionNote(),
            r.round(),
            materials
        );
    }

    /** 引用元数据从当前有效切片重新解析，不能直接采信模型返回的文件归属或定位。 */
    public Citation citation(Identity actor, long id, long chunkId) {
        var view = view(actor, id);
        if (
            view.report() == null ||
            view
                .report()
                .evidence()
                .stream()
                .noneMatch(e -> e.id().equals(Long.toString(chunkId)))
        ) {
            throw new AccessDenied();
        }
        var task = task(actor.tenantId(), id, false);
        var r = review(task);
        var base = reference(actor, task.getCaseId(), r.baseId());
        if (base == null) {
            throw new AccessDenied();
        }
        var live = knowledge.liveCitation(actor.tenantId(), r.baseId(), base.caseId(), chunkId);
        if (live == null) {
            throw new AccessDenied();
        }
        return new Citation(
            Long.toString(chunkId),
            live.text(),
            base.scope(),
            r.baseId().toString(),
            Long.toString(live.fileId()),
            Long.toString(live.fileVersionId()),
            live.fileName(),
            live.fileVersion(),
            live.locatorKind(),
            live.locatorIndex(),
            live.startOffset(),
            live.endOffset()
        );
    }

    public DownloadResult downloadCitation(Identity actor, long id, long chunkId) throws Exception {
        var c = citation(actor, id, chunkId);
        var v = versions.selectOne(
            new LambdaQueryWrapper<FileVersionModel>()
                .eq(FileVersionModel::getTenantId, actor.tenantId())
                .eq(FileVersionModel::getId, Long.parseLong(c.fileVersionId()))
                .eq(FileVersionModel::getFileId, Long.parseLong(c.fileId()))
        );
        if (v == null) {
            throw new AccessDenied();
        }
        var in = storage.open(v.getObjectKey());
        try {
            audit(task(actor.tenantId(), id, false), actor.userId(), "contract:reference-download");
        } catch (RuntimeException ex) {
            in.close();
            throw ex;
        }
        return new DownloadResult(in, v.getMimeType(), v.getSizeBytes(), c.fileName());
    }

    public List<ContractReviewRepository.Candidate> candidates(Identity actor, long id) {
        view(actor, id);
        var task = task(actor.tenantId(), id, false);
        return reviews.candidates(actor.tenantId(), task.getCaseId());
    }

    /**
     * 只为等待复核的报告登记新的补材轮次，并要求显式确认新一轮模型费用。
     * 重复请求须匹配原版本、提交者、说明及材料；新轮次保存前一报告快照后重新投递。
     */
    @Transactional
    public Requested supplement(
        Identity actor,
        long id,
        int expectedVersion,
        String requestId,
        List<Long> parseTaskIds,
        String instructions,
        boolean costConfirmed
    ) {
        // 补材属于显式确认费用的新一轮审查；先复核原报告和固定材料，再原子登记轮次及投递事件。
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        authorize(actor, task, Action.UPDATE, true);
        var r = review(task);
        if (
            !costConfirmed ||
            requestId == null ||
            !requestId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") ||
            parseTaskIds == null ||
            parseTaskIds.isEmpty() ||
            parseTaskIds.size() > 5 ||
            parseTaskIds.stream().anyMatch(p -> p == null || p < 1) ||
            new HashSet<>(parseTaskIds).size() != parseTaskIds.size() ||
            instructions == null ||
            instructions.length() > 1000
        ) {
            throw BusinessException.badRequest(
                "请选择1至5项已解析材料、提供请求标识并确认新一轮模型费用，说明最多1000字"
            );
        }
        instructions = instructions.trim();
        var ids = parseTaskIds.stream().sorted().toList();
        var owner = creator(task);
        authorize(owner, task, Action.READ, false);
        reference(actor, task.getCaseId(), r.baseId());
        reference(owner, task.getCaseId(), r.baseId());
        if (!modelIdentity(configs.llmForQuestion(task.getTenantId())).equals(r.modelIdentity())) {
            throw BusinessException.conflict("模型配置已变化，请重新提交审查");
        }
        if (
            Set.of("DELIVERY", "UNCERTAIN").contains(Objects.toString(r.waitingReason(), "")) ||
            reviews.currentDead(task.getTenantId(), id, task.getAttempt(), task.getStateVersion())
        ) {
            throw BusinessException.conflict("此审查需先处理投递或不确定执行，不能补材");
        }
        var existing = reviews
            .rounds(actor.tenantId(), id)
            .stream()
            .filter(round -> round.requestId().equals(requestId))
            .findFirst()
            .orElse(null);
        if (existing != null) {
            var oldIds = reviews
                .supplements(actor.tenantId(), id)
                .stream()
                .filter(s -> s.round() == existing.number())
                .map(ContractReviewRepository.Supplement::parseTaskId)
                .sorted()
                .toList();
            if (
                existing.createdBy() != actor.userId() ||
                existing.fromStateVersion() != expectedVersion ||
                !existing.instructions().equals(instructions) ||
                !oldIds.equals(ids)
            ) {
                throw BusinessException.conflict("请求标识已用于其他补材内容");
            }
            supplements(actor, task);
            supplements(owner, task);
            return requested(task);
        }
        if (
            expectedVersion != task.getStateVersion() ||
            !"WAITING_HUMAN".equals(task.getStatus()) ||
            !"REVIEW".equals(r.waitingReason()) ||
            r.reportJson() == null
        ) {
            throw BusinessException.conflict("仅待复核的报告可以补材续审；请刷新当前状态");
        }
        if (r.round() >= 10) {
            throw BusinessException.conflict("此合同已续审10轮，请核对后提交新的审查");
        }
        var report = decode(r.reportJson());
        report.validate();
        validateEvidence(actor, task, r, report);
        var prior = supplements(actor, task);
        supplements(owner, task);
        var used = prior.stream().map(Supplement::parseTaskId).collect(Collectors.toSet());
        var sources = new ArrayList<KnowledgeRepository.SourceRow>();
        int total = prior
            .stream()
            .mapToInt(s -> s.text().length())
            .sum();
        for (long parseId : ids) {
            if (used.contains(Long.toString(parseId))) {
                throw BusinessException.conflict("该材料版本已用于续审，请选择新增或更新的材料");
            }
            var source = knowledge.source(actor.tenantId(), task.getCaseId(), parseId);
            if (source == null) {
                throw new AccessDenied();
            }
            material(source);
            total += source.text().length();
            sources.add(source);
        }
        if (total > 40000) {
            throw BusinessException.badRequest("累计补充材料超过4万字，请拆分后提交新的审查");
        }
        try {
            reviews.addRound(
                actor.tenantId(),
                id,
                r.round() + 1,
                requestId,
                expectedVersion,
                actor.userId(),
                instructions,
                r.reportJson()
            );
            for (var source : sources) {
                reviews.addSupplement(actor.tenantId(), id, r.round() + 1, source);
            }
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("补材请求标识已用于另一审查");
        }
        if (
            reviews.transition(
                actor.tenantId(),
                id,
                task.getAttempt(),
                expectedVersion,
                "QUEUED",
                null
            ) != 1
        ) {
            throw BusinessException.conflict("任务状态已变化");
        }
        task.setStatus("QUEUED");
        task.setStateVersion(expectedVersion + 1);
        event(task);
        audit(task, actor.userId(), "contract:supplement");
        return requested(task);
    }

    /**
     * 操作者与原创建者均保有资料访问权时才接受人工决定；相同决定与意见可幂等重交。
     * 决定保存后继续工作流发布报告，不在人工确认接口中直接置为成功。
     */
    @Transactional
    public Requested approve(
        Identity actor,
        long id,
        int expectedVersion,
        String decisionId,
        String note
    ) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        authorize(actor, task, Action.UPDATE, true);
        var r = review(task);
        reference(actor, task.getCaseId(), r.baseId());
        authorize(creator(task), task, Action.READ, false);
        reference(creator(task), task.getCaseId(), r.baseId());
        supplements(actor, task);
        supplements(creator(task), task);
        if (
            decisionId == null ||
            !decisionId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") ||
            note == null ||
            note.isBlank() ||
            note.length() > 500
        ) {
            throw BusinessException.badRequest("请填写复核意见并提供决定标识");
        }
        note = note.trim();
        if (Set.of("DELIVERY", "UNCERTAIN").contains(Objects.toString(r.waitingReason(), ""))) {
            throw BusinessException.conflict("此任务需处理投递或核对不确定执行，不能确认复核");
        }
        if (r.decisionId() != null) {
            if (
                r.decisionId().equals(decisionId) &&
                Objects.equals(r.decidedBy(), actor.userId()) &&
                r.decisionNote().equals(note)
            ) {
                return requested(task);
            }
            throw BusinessException.conflict("该审查已有复核决定");
        }
        if (
            task.getStateVersion() != expectedVersion ||
            !"WAITING_HUMAN".equals(task.getStatus()) ||
            r.reportJson() == null ||
            !"REVIEW".equals(r.waitingReason())
        ) {
            throw BusinessException.conflict(
                "此任务不能确认，请刷新；不确定的模型请求需取消后重新提交"
            );
        }
        validateEvidence(actor, task, r, decode(r.reportJson()));
        try {
            reviews.decision(actor.tenantId(), id, decisionId, actor.userId(), note);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("决定标识已用于其他审查");
        }
        if (
            reviews.transition(
                actor.tenantId(),
                id,
                task.getAttempt(),
                expectedVersion,
                "RUNNING",
                null
            ) != 1
        ) {
            throw BusinessException.conflict("任务状态已变化");
        }
        // 人工决定先持久化，再投递同一工作流完成报告发布；此处不直接宣告任务成功。
        task.setStatus("RUNNING");
        task.setStateVersion(expectedVersion + 1);
        event(task);
        audit(task, actor.userId(), "contract:approve");
        return requested(task);
    }

    /** 重新投递同一次执行；沿用执行批次与模型记账，不授权另一轮模型调用。 */
    @Transactional
    public Requested recover(Identity actor, long id, int expectedVersion) {
        taskViews.get(actor, id);
        var task = task(actor.tenantId(), id, true);
        authorize(actor, task, Action.UPDATE, true);
        var r = review(task);
        reference(actor, task.getCaseId(), r.baseId());
        authorize(creator(task), task, Action.READ, false);
        reference(creator(task), task.getCaseId(), r.baseId());
        supplements(actor, task);
        supplements(creator(task), task);
        if (
            task.getStateVersion() != expectedVersion ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            throw BusinessException.conflict("仅可恢复排队或执行中的审查任务");
        }
        execution(task, task.getAttempt(), expectedVersion);
        event(task);
        audit(task, actor.userId(), "contract:recover");
        return requested(task);
    }

    /** 首次执行将排队任务推进为 RUNNING，返回推进后的版本；重复领取不重复推进。 */
    @Transactional
    public Input begin(long id, int attempt, int version) {
        var task = tasks.selectLockById(id);
        if (task == null) {
            throw new AccessDenied();
        }
        execution(task, attempt, version);
        var actor = creator(task);
        authorize(actor, task, Action.READ, true);
        reference(actor, task.getCaseId(), review(task).baseId());
        if ("QUEUED".equals(task.getStatus())) {
            if (
                reviews.transition(task.getTenantId(), id, attempt, version, "RUNNING", null) != 1
            ) {
                throw BusinessException.conflict("审查状态已变化");
            }
            version++;
        }
        return input(id, attempt, version);
    }

    public Input input(long id, int attempt, int version) {
        var task = tasks.selectById(id);
        if (task == null) {
            throw BusinessException.notFound("审查任务不存在");
        }
        execution(task, attempt, version);
        return fixedInput(task, attempt, version, true);
    }

    /** 基于创建者当前权限构造固定输入；includeKey=false 用于资格核对，避免向核对端传递密钥。 */
    private Input fixedInput(AsyncTaskModel task, int attempt, int version, boolean includeKey) {
        var actor = creator(task);
        authorize(actor, task, Action.READ, false);
        var r = review(task);
        reference(actor, task.getCaseId(), r.baseId());
        var source = tasks.getTaskContext(task.getId());
        if (source == null) {
            throw BusinessException.notFound("合同材料不可用");
        }
        var materials = supplements(actor, task);
        var rounds = reviews.rounds(task.getTenantId(), task.getId());
        var round = rounds
            .stream()
            .filter(v -> v.number() == r.round())
            .findFirst()
            .orElse(null);
        if (r.round() > 0 && round == null) {
            throw new IllegalStateException("补材轮次不存在");
        }
        var model = configs.llmForQuestion(task.getTenantId());
        if (!modelIdentity(model).equals(r.modelIdentity())) {
            throw BusinessException.conflict("模型配置已变化，请重新提交审查");
        }
        return new Input(
            task.getTenantId().toString(),
            task.getId().toString(),
            task.getResourceVersionId().toString(),
            source.get("sha256").toString(),
            source.get("mime_type").toString(),
            attempt,
            version,
            r.stance(),
            r.focus(),
            r.baseId() == null ? null : r.baseId().toString(),
            model.provider(),
            model.baseUrl(),
            model.model(),
            includeKey ? model.apiKey() : null,
            r.decisionId(),
            r.round(),
            round == null ? "" : round.instructions(),
            materials,
            round == null ? null : decode(round.reportJson())
        );
    }

    /** 核对当前登记的死信轮次及模型执行记账协议，随后返回不含 API Key 的恢复检查输入。 */
    public Input recoveryInput(Identity actor, long id, int attempt, int registeredStateVersion) {
        taskViews.get(actor, id);
        var t = task(actor.tenantId(), id, true);
        var r = review(t);
        if (r.executionProtocol() != 2) {
            throw BusinessException.conflict("历史任务无法确认模型记账，请取消并重新提交");
        }
        if (
            t.getAttempt() != attempt ||
            t.getStateVersion() != registeredStateVersion ||
            !"WAITING_HUMAN".equals(t.getStatus()) ||
            !Set.of("DELIVERY", "UNCERTAIN").contains(Objects.toString(r.waitingReason(), "")) ||
            !reviews.currentDead(t.getTenantId(), id, attempt, registeredStateVersion)
        ) {
            throw BusinessException.conflict("审查投递状态已变化");
        }
        return currentRecoveryInput(actor, id);
    }

    /** 重新核对双方权限、原合同、累计补材和既有报告；返回固定输入本身不授权再次调用模型。 */
    public Input currentRecoveryInput(Identity actor, long id) {
        taskViews.get(actor, id);
        var t = task(actor.tenantId(), id, true);
        var r = review(t);
        cases.require(actor, "task:cancel");
        authorize(actor, t, Action.UPDATE, true);
        reference(actor, t.getCaseId(), r.baseId());
        supplements(actor, t);
        var creator = creator(t);
        authorize(creator, t, Action.READ, false);
        reference(creator, t.getCaseId(), r.baseId());
        supplements(creator, t);
        for (var round : reviews.rounds(t.getTenantId(), id)) {
            if (round.number() == r.round() && round.reportJson() != null) {
                validateEvidence(actor, t, r, decode(round.reportJson()));
                validateEvidence(creator, t, r, decode(round.reportJson()));
            }
        }
        if (r.reportJson() != null) {
            validateEvidence(actor, t, r, decode(r.reportJson()));
            validateEvidence(creator, t, r, decode(r.reportJson()));
        }
        return fixedInput(t, t.getAttempt(), t.getStateVersion(), false);
    }

    public List<ContractReport.Evidence> recoveryEvidence(Identity actor, long id) {
        currentRecoveryInput(actor, id);
        var t = task(actor.tenantId(), id, false);
        var r = review(t);
        return r.reportJson() == null ? List.of() : decode(r.reportJson()).evidence();
    }

    /** 必须加入通用投递处理的现有事务，等待原因与任务状态版本一起提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDeliveryWaiting(AsyncTaskModel task, String kind) {
        review(task);
        reviews.deliveryReason(
            task.getTenantId(),
            task.getId(),
            "UNCERTAIN".equals(kind) ? "UNCERTAIN" : "DELIVERY"
        );
    }

    /** 仅清除采用记账协议的 DELIVERY 等待；不确定模型执行不能用此方法转为普通重试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearDeliveryWaiting(AsyncTaskModel task) {
        var r = review(task);
        if (r.executionProtocol() != 2 || !"DELIVERY".equals(r.waitingReason())) {
            throw BusinessException.conflict("审查不可恢复");
        }
        reviews.deliveryReason(task.getTenantId(), task.getId(), null);
    }

    public Identity executionActor(long id, int attempt, int version) {
        var task = tasks.selectById(id);
        if (task == null) {
            throw new AccessDenied();
        }
        execution(task, attempt, version);
        var actor = creator(task);
        authorize(actor, task, Action.READ, false);
        reference(actor, task.getCaseId(), review(task).baseId());
        supplements(actor, task);
        return actor;
    }

    /**
     * 当前执行轮次才能提交结果，迟到回调返回 false；报告与任务状态同事务更新。
     * 续审须保留原合同正文及条款，人工已确认的报告不能被后续回调替换。
     */
    @Transactional
    public boolean apply(
        long tenant,
        long id,
        int attempt,
        int version,
        String outcome,
        ContractReport report
    ) {
        var task = tasks.selectByIdForUpdate(tenant, id);
        if (task == null || !"CONTRACT_REVIEW".equals(task.getType())) {
            return false;
        }
        if (
            task.getAttempt() != attempt ||
            task.getStateVersion() != version ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            return false;
        }
        var r = review(task);
        String status,
            reason = null,
            error = null;
        // REVIEW 表示报告待复核；UNCERTAIN/DELIVERY 表示执行或投递需核对，不能用复核绕过。
        if ("FAILED".equals(outcome)) {
            status = "FAILED";
            report = null;
            error = "合同审查失败，请检查材料、模型配置及服务状态";
        } else if ("UNCERTAIN".equals(outcome)) {
            status = "WAITING_HUMAN";
            reason = "UNCERTAIN";
            report = null;
            error = "模型请求结果不确定，不会自动重复调用；请取消后确认是否重新提交";
        } else {
            var actor = creator(task);
            authorize(actor, task, Action.READ, true);
            if (!modelIdentity(configs.llmForQuestion(tenant)).equals(r.modelIdentity())) {
                throw BusinessException.conflict("模型配置已变化，请重新提交审查");
            }
            if (report == null) {
                throw BusinessException.badRequest("审查报告缺失");
            }
            report.validate();
            if (r.round() > 0) {
                var previous = reviews
                    .rounds(tenant, id)
                    .stream()
                    .filter(v -> v.number() == r.round())
                    .findFirst()
                    .orElseThrow();
                var original = decode(previous.reportJson());
                if (
                    !original.sourceText().equals(report.sourceText()) ||
                    !original.clauses().equals(report.clauses()) ||
                    !original.contractType().equals(report.contractType())
                ) {
                    throw BusinessException.conflict("续审不能更换原合同或原条款");
                }
            }
            if (
                tasks.sourceMatches(
                    tenant,
                    task.getCaseId(),
                    task.getResourceVersionId(),
                    report.sourceSha256()
                ) != 1
            ) {
                throw new AccessDenied();
            }
            validateEvidence(actor, task, r, report);
            supplements(actor, task);
            if (r.decisionId() != null && !Objects.equals(decode(r.reportJson()), report)) {
                throw BusinessException.conflict("已复核的报告不可改变");
            }
            if ("WAITING_HUMAN".equals(outcome) && r.decisionId() == null && report.needsHuman()) {
                status = "WAITING_HUMAN";
                reason = "REVIEW";
            } else if (
                "SUCCEEDED".equals(outcome) && (!report.needsHuman() || r.decisionId() != null)
            ) {
                status = "SUCCEEDED";
            } else {
                throw BusinessException.conflict("审查结果与人工复核状态不一致");
            }
        }
        if (reviews.transition(tenant, id, attempt, version, status, error) != 1) {
            return false;
        }
        reviews.result(tenant, id, report == null ? null : encode(report), reason);
        audit(task, null, "contract:result");
        return true;
    }

    private KnowledgeRepository.BaseRow reference(Identity actor, long caseId, Long baseId) {
        if (baseId == null) {
            return null;
        }
        var base = access.query(actor, baseId);
        // 合同可引用同案资料或本租户独立上传的公共知识，不能引用其他案件知识库。
        if ("CASE".equals(base.scope()) && !Objects.equals(base.caseId(), caseId)) {
            throw new AccessDenied();
        }
        return base;
    }

    private void validateEvidence(
        Identity actor,
        AsyncTaskModel task,
        ContractReviewRepository.Review r,
        ContractReport report
    ) {
        long tenant = actor.tenantId();
        var base = reference(actor, task.getCaseId(), r.baseId());
        if (
            tasks.sourceMatches(
                tenant,
                task.getCaseId(),
                task.getResourceVersionId(),
                report.sourceSha256()
            ) != 1
        ) {
            throw new AccessDenied();
        }
        if (!report.evidence().isEmpty()) {
            // 引用须对应当前模型可检索的有效文档，且切片正文逐字匹配保存的报告依据。
            if (r.baseId() == null) {
                throw BusinessException.conflict("报告引用了未选择的知识库");
            }
            String current = modelIdentity(configs.embeddingForTask(tenant));
            var active = knowledge
                .searchSources(tenant, r.baseId(), base.caseId())
                .stream()
                .filter(s -> current.equals(s.modelIdentity()))
                .map(KnowledgeRepository.SearchSourceRow::documentId)
                .collect(Collectors.toSet());
            for (var evidence : report.evidence()) {
                long id;
                try {
                    id = Long.parseLong(evidence.id());
                } catch (Exception ex) {
                    throw BusinessException.badRequest("引用编号无效");
                }
                var live = knowledge.liveCitation(tenant, r.baseId(), base.caseId(), id);
                if (
                    live == null ||
                    !active.contains(live.documentId()) ||
                    !live.text().equals(evidence.text())
                ) {
                    throw BusinessException.conflict("报告依据已失效，请重新提交审查");
                }
            }
        }
    }

    private void authorize(Identity actor, AsyncTaskModel task, Action action, boolean lock) {
        // 分别检查任务、AI 操作、文件读取权限，再确认提交时固定的合同版本仍可用。
        cases.require(actor, "task:read");
        cases.authorize(actor, task.getCaseId(), action, "ai:review", lock);
        cases.authorize(actor, task.getCaseId(), Action.READ, "file:read", false);
        if (
            tasks.countAvailableSource(
                actor.tenantId(),
                task.getCaseId(),
                task.getResourceVersionId()
            ) != 1
        ) {
            throw new AccessDenied();
        }
    }

    /** 每次执行或交付时把累计补材与当前解析来源逐字比对，删除、换版或定位变更都会失效。 */
    private List<Supplement> supplements(Identity actor, AsyncTaskModel task) {
        var result = new ArrayList<Supplement>();
        for (var s : reviews.supplements(task.getTenantId(), task.getId())) {
            var live = knowledge.source(actor.tenantId(), task.getCaseId(), s.parseTaskId());
            if (
                live == null ||
                live.fileVersionId() != s.fileVersionId() ||
                !live.sha256().equals(s.sha256()) ||
                !live.text().equals(s.text()) ||
                !Objects.equals(live.segmentsJson(), s.segmentsJson())
            ) {
                throw new AccessDenied();
            }
            result.add(material(live));
        }
        return List.copyOf(result);
    }

    private Supplement material(KnowledgeRepository.SourceRow source) {
        if (
            source.text() == null ||
            source.text().isBlank() ||
            source.text().length() > 40000 ||
            source.segmentsJson() == null
        ) {
            throw BusinessException.badRequest("补材需先完成带页段定位的解析，单份最多4万字");
        }
        List<Segment> segments;
        try {
            segments = json.readValue(source.segmentsJson(), new TypeReference<List<Segment>>() {});
        } catch (Exception ex) {
            throw BusinessException.badRequest("材料页段定位无效");
        }
        if (segments.isEmpty() || segments.size() > 5000) {
            throw BusinessException.badRequest("材料页段定位无效");
        }
        int end = 0,
            index = 0;
        for (var s : segments) {
            if (
                s == null ||
                !Set.of("PAGE", "PARAGRAPH").contains(s.kind()) ||
                s.index() <= index ||
                s.startOffset() < end ||
                s.endOffset() <= end ||
                s.endOffset() > source.text().length()
            ) {
                throw BusinessException.badRequest("材料页段定位无效");
            }
            end = s.endOffset();
            index = s.index();
        }
        return new Supplement(
            Long.toString(source.taskId()),
            Long.toString(source.fileVersionId()),
            source.sha256(),
            source.fileName(),
            source.text(),
            List.copyOf(segments)
        );
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

    private AsyncTaskModel task(long tenant, long id, boolean lock) {
        var t = lock
            ? tasks.selectByIdForUpdate(tenant, id)
            : tasks.selectOne(
                  new LambdaQueryWrapper<AsyncTaskModel>()
                      .eq(AsyncTaskModel::getTenantId, tenant)
                      .eq(AsyncTaskModel::getId, id)
              );
        if (t == null || !"CONTRACT_REVIEW".equals(t.getType())) {
            throw new AccessDenied();
        }
        return t;
    }

    private ContractReviewRepository.Review review(AsyncTaskModel task) {
        var r = reviews.get(task.getTenantId(), task.getId());
        if (r == null) {
            throw BusinessException.notFound("审查输入不存在");
        }
        return r;
    }

    private void execution(AsyncTaskModel task, int attempt, int version) {
        if (
            Set.of("DELIVERY", "UNCERTAIN").contains(
                Objects.toString(review(task).waitingReason(), "")
            )
        ) {
            throw BusinessException.conflict("审查任务等待安全处理");
        }
        if (
            !"CONTRACT_REVIEW".equals(task.getType()) ||
            task.getAttempt() != attempt ||
            task.getStateVersion() != version ||
            !Set.of("QUEUED", "RUNNING").contains(task.getStatus())
        ) {
            throw BusinessException.conflict("审查任务状态已变化");
        }
    }

    private String modelIdentity(AiConfigurationService.InternalModel m) {
        return encode(List.of(m.provider().trim(), m.baseUrl().trim(), m.model().trim()));
    }

    private Requested requested(AsyncTaskModel task) {
        return new Requested(task.getId().toString(), task.getStatus(), task.getStateVersion());
    }

    private ContractReport decode(String value) {
        try {
            return json.readValue(value, ContractReport.class);
        } catch (Exception ex) {
            throw new IllegalStateException("审查报告损坏");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("审查数据无法序列化");
        }
    }

    private void event(AsyncTaskModel task) {
        String event = UUID.randomUUID().toString();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", event);
        payload.put("schemaVersion", "1.0");
        payload.put("tenantId", task.getTenantId().toString());
        payload.put("taskId", task.getId().toString());
        payload.put("attempt", task.getAttempt());
        payload.put("stateVersion", task.getStateVersion());
        payload.put("resourceVersionId", task.getResourceVersionId().toString());
        payload.put("traceId", TraceContext.currentOrNew());
        payload.put("occurredAt", Instant.now().toString());
        outbox.insert(
            OutboxEventModel.builder()
                .eventId(event)
                .tenantId(task.getTenantId())
                .taskId(task.getId())
                .routingKey("contract.review.requested.v1")
                .payload(encode(payload))
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
                .objectType("contract_review")
                .objectId(task.getId().toString())
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .build()
        );
    }
}
