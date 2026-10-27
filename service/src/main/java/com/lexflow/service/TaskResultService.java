package com.lexflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.TaskParseResultMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.identity.Identity;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务结果落库服务：供内部接口调用，幂等处理 AI 服务回传的成功/失败结果。
 * 安全约束：只有当 attempt 和 stateVersion 与数据库完全一致时才执行更新，
 * 防止迟到消息覆盖取消、重试或已结束的任务。专用业务结果须走各自的校验接口。
 */
@Service
public class TaskResultService extends BaseService<AsyncTaskModel, AsyncTaskMapper> {

    private static final Logger log = LoggerFactory.getLogger(TaskResultService.class);
    private final AuditLogMapper auditLogMapper;
    private final TaskParseResultMapper parseResults;
    private final ObjectMapper json;
    private final KnowledgeAccessService knowledge;
    private final SysUserMapper users;

    /**
     * 解析正文的页或段位置：index 从 1 开始，字符区间为左闭右开 [startOffset, endOffset)。
     * 偏移按 Java String 的 UTF-16 字符单元计算，须与保存的 parsedText 使用同一份正文。
     */
    public record SourceSegment(String kind, int index, int startOffset, int endOffset) {}

    public TaskResultService(
        AsyncTaskMapper mapper,
        AuditLogMapper auditLogMapper,
        TaskParseResultMapper parseResults,
        ObjectMapper json,
        KnowledgeAccessService knowledge,
        SysUserMapper users
    ) {
        super(mapper);
        this.auditLogMapper = auditLogMapper;
        this.parseResults = parseResults;
        this.json = json;
        this.knowledge = knowledge;
        this.users = users;
    }

    /** 不携带解析正文的通用回调入口；成功的 DOCUMENT_PARSE 结果须使用含正文的重载。 */
    @Transactional
    public void applyResult(
        long taskId,
        int attempt,
        int stateVersion,
        boolean success,
        String resultObjectKey,
        String errorMessage
    ) {
        applyResult(
            taskId,
            attempt,
            stateVersion,
            success,
            resultObjectKey,
            errorMessage,
            null,
            null,
            null
        );
    }

    /** 兼容没有页段位置的解析回调；segments 为 null 时仅保存正文与来源摘要。 */
    @Transactional
    public void applyResult(
        long taskId,
        int attempt,
        int stateVersion,
        boolean success,
        String resultObjectKey,
        String errorMessage,
        String parsedText,
        String sourceSha256
    ) {
        applyResult(
            taskId,
            attempt,
            stateVersion,
            success,
            resultObjectKey,
            errorMessage,
            parsedText,
            sourceSha256,
            null
        );
    }

    /**
     * 接受当前执行轮次的结果；任务不存在、版本过期或已停止接收结果时忽略回调。
     * 成功解析先校验固定来源，再将任务终态、正文、定位和审计记录一起提交。
     */
    @Transactional
    public void applyResult(
        long taskId,
        int attempt,
        int stateVersion,
        boolean success,
        String resultObjectKey,
        String errorMessage,
        String parsedText,
        String sourceSha256,
        List<SourceSegment> segments
    ) {
        AsyncTaskModel taskModel = mapper.selectLockById(taskId);
        if (taskModel == null) {
            log.warn("[task-result] task {} not found, ignoring", taskId);
            return;
        }

        int currentAttempt = taskModel.getAttempt() != null ? taskModel.getAttempt() : 0;
        int currentStateVersion =
            taskModel.getStateVersion() != null ? taskModel.getStateVersion() : 1;

        if (currentAttempt != attempt || currentStateVersion != stateVersion) {
            log.info(
                "[task-result] task={} stale result (dbAttempt={} attempt={} dbSV={} sv={}), skipping",
                taskId,
                currentAttempt,
                attempt,
                currentStateVersion,
                stateVersion
            );
            return;
        }

        if (!"QUEUED".equals(taskModel.getStatus()) && !"RUNNING".equals(taskModel.getStatus())) {
            log.info(
                "[task-result] task={} no longer accepts results (status={})",
                taskId,
                taskModel.getStatus()
            );
            return;
        }

        validateResultType(taskModel, success);
        if (success && "DOCUMENT_PARSE".equals(taskModel.getType())) {
            validateParsedResult(taskModel, taskId, parsedText, sourceSha256, segments);
        }

        String newStatus = success ? "SUCCEEDED" : "FAILED";
        int updated = mapper.applyTaskResult(
            taskId,
            attempt,
            stateVersion,
            newStatus,
            resultObjectKey,
            errorMessage
        );

        if (updated == 0) {
            log.warn("[task-result] concurrent update race on task={}, result ignored", taskId);
            return;
        }

        if (success && "DOCUMENT_PARSE".equals(taskModel.getType())) {
            // 解析正文、来源定位和任务终态同事务保存，避免出现成功但没有可追溯结果的任务。
            String segmentsJson = serializeSegments(segments);
            parseResults.insert(
                taskModel.getTenantId(),
                taskId,
                sourceSha256,
                parsedText,
                segmentsJson
            );
        }

        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(taskModel.getTenantId())
                .actorId(null)
                .action("task:result")
                .objectType("task")
                .objectId(Long.toString(taskId))
                .result(newStatus)
                .traceId(TraceContext.currentOrNew())
                .detailJson("{\"success\":" + success + "}")
                .build()
        );

        log.info("[task-result] task={} -> {} (attempt={})", taskId, newStatus, attempt);
    }

    /** 专用报告任务不能通过通用成功回调绕过人工复核和报告来源校验。 */
    private static void validateResultType(AsyncTaskModel taskModel, boolean success) {
        if ("EXPORT".equals(taskModel.getType())) {
            throw new IllegalArgumentException("导出任务由 Java 专用消费者处理");
        }
        if ("DOCUMENT_GENERATE".equals(taskModel.getType())) {
            throw new IllegalArgumentException("文书生成须通过专用版本结果接口");
        }
        if ("CASE_ANALYSIS".equals(taskModel.getType())) {
            throw new IllegalArgumentException("案件分析须通过专用报告及复核接口");
        }
        if (success && "CONTRACT_REVIEW".equals(taskModel.getType())) {
            throw new IllegalArgumentException("合同审查必须通过专用报告接口及复核校验");
        }
    }

    private void validateParsedResult(
        AsyncTaskModel taskModel,
        long taskId,
        String parsedText,
        String sourceSha256,
        List<SourceSegment> segments
    ) {
        if (
            parsedText == null ||
            parsedText.isBlank() ||
            parsedText.length() > 1_000_000 ||
            sourceSha256 == null ||
            !sourceSha256.matches("[a-f0-9]{64}")
        ) {
            throw new IllegalArgumentException("解析结果缺失或超过 100 万字符");
        }
        // 不信任回传的摘要：必须与提交时锁定的源文件版本一致。
        boolean sourceMatchesTask = taskModel.getResourceVersionId() != null;
        if (sourceMatchesTask && taskModel.getCaseId() != null) {
            sourceMatchesTask =
                mapper.sourceMatches(
                    taskModel.getTenantId(),
                    taskModel.getCaseId(),
                    taskModel.getResourceVersionId(),
                    sourceSha256
                ) == 1;
        } else if (sourceMatchesTask) {
            // 公共知识没有 caseId；重新恢复创建者的当前身份，并核对来源仍绑定同一公共知识库。
            Long baseId = mapper.tenantSourceBaseForTask(taskModel.getTenantId(), taskId);
            var creator =
                taskModel.getCreatedBy() == null
                    ? null
                    : users.selectById(taskModel.getCreatedBy());
            if (baseId == null || creator == null) {
                sourceMatchesTask = false;
            } else {
                var base = knowledge.manage(
                    new Identity(
                        creator.getId(),
                        taskModel.getTenantId(),
                        creator.getSessionVersion(),
                        creator.getDisplayName()
                    ),
                    baseId
                );
                sourceMatchesTask =
                    "TENANT".equals(base.scope()) &&
                    base.caseId() == null &&
                    mapper.tenantSourceMatches(
                        taskModel.getTenantId(),
                        baseId,
                        taskModel.getResourceVersionId(),
                        sourceSha256
                    ) == 1;
            }
        }
        if (!sourceMatchesTask) {
            throw new IllegalArgumentException("解析结果与源文件版本不匹配");
        }
        validateSegments(parsedText, segments);
    }

    private String serializeSegments(List<SourceSegment> segments) {
        try {
            return segments == null ? null : json.writeValueAsString(segments);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("来源位置无法序列化", ex);
        }
    }

    private static void validateSegments(String text, List<SourceSegment> segments) {
        // 旧任务回调没有位置字段；新解析器提供位置时必须严格验证后落库。
        if (segments == null) {
            return;
        }
        if (segments.isEmpty() || segments.size() > 5000) {
            throw new IllegalArgumentException("来源位置数量无效");
        }
        int previousEnd = 0;
        int previousIndex = 0;
        for (SourceSegment segment : segments) {
            if (
                segment == null ||
                !("PAGE".equals(segment.kind()) || "PARAGRAPH".equals(segment.kind())) ||
                segment.index() <= previousIndex ||
                segment.startOffset() < previousEnd ||
                segment.startOffset() < 0 ||
                segment.endOffset() <= segment.startOffset() ||
                segment.endOffset() > text.length()
            ) {
                throw new IllegalArgumentException("来源位置与解析原文不一致");
            }
            previousEnd = segment.endOffset();
            previousIndex = segment.index();
        }
    }
}
