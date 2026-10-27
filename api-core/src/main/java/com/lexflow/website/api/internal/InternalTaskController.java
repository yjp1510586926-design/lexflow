package com.lexflow.website.api.internal;

import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.TaskResultService;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.website.biz.TaskContextBiz;
import com.lexflow.website.biz.dto.ApplyTaskResultDTO;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 内部 API — 仅供 AI 服务调用（Shared-Secret 认证）。
 * 路由前缀 /internal/v1，在 Spring Security 链中免用户登录和 CSRF，
 * 仍由 InternalSecurityFilter 校验 X-Internal-Secret，材料读取另行校验创建人的当前权限。
 *
 * 接口：
 *   GET  /internal/v1/tasks/{taskId}/context   — 获取任务上下文（含固定文件版本元数据）
 *   GET  /internal/v1/tasks/{taskId}/source    — 按执行轮次重新授权并读取原件
 *   POST /internal/v1/tasks/{taskId}/results   — 回传结果（幂等，按 attempt+stateVersion 防重复）
 */
@RestController
@RequestMapping("/internal/v1/tasks")
public class InternalTaskController {

    private final TaskContextBiz contexts;
    private final TaskResultService taskResults;
    private final PrivateObjectStorage storage;

    public InternalTaskController(
        TaskContextBiz contexts,
        TaskResultService taskResults,
        PrivateObjectStorage storage
    ) {
        this.contexts = contexts;
        this.taskResults = taskResults;
        this.storage = storage;
    }

    /** 获取任务上下文：taskId、tenantId、type、resourceVersionId 及关联文件版本元数据 */
    @GetMapping("/{taskId}/context")
    public Map<String, Object> getContext(@PathVariable long taskId) {
        Map<String, Object> taskContext = contexts.get(taskId);
        if (taskContext == null || taskContext.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
        }
        // ID 对外使用字符串，防止 AI 服务和 JavaScript 客户端丢失 64 位整数精度。
        var result = new LinkedHashMap<String, Object>();
        result.put("scope", taskContext.get("scope"));
        result.put(
            "knowledgeBaseId",
            taskContext.get("knowledge_base_id") == null
                ? null
                : taskContext.get("knowledge_base_id").toString()
        );
        result.put(
            "caseId",
            taskContext.get("case_id") == null ? null : taskContext.get("case_id").toString()
        );
        result.put(
            "resourceVersionId",
            taskContext.get("resource_version_id") == null
                ? null
                : taskContext.get("resource_version_id").toString()
        );
        result.putAll(
            Map.ofEntries(
                Map.entry("status", taskContext.get("status").toString()),
                Map.entry("taskId", taskContext.get("id").toString()),
                Map.entry("tenantId", taskContext.get("tenant_id").toString()),
                Map.entry("type", taskContext.get("type").toString()),
                Map.entry("attempt", taskContext.get("attempt")),
                Map.entry("stateVersion", taskContext.get("state_version")),
                Map.entry("idempotencyKey", taskContext.get("idempotency_key")),
                Map.entry("fileObjectKey", taskContext.getOrDefault("object_key", "")),
                Map.entry("mimeType", taskContext.getOrDefault("mime_type", "")),
                Map.entry("sha256", taskContext.getOrDefault("sha256", ""))
            )
        );
        return result;
    }

    /** 每次下载重新授权；版本不匹配或非执行态时不得读取对象。 */
    @GetMapping("/{taskId}/source")
    public ResponseEntity<InputStreamResource> source(
        @PathVariable long taskId,
        @RequestParam int attempt,
        @RequestParam int stateVersion
    ) throws Exception {
        var taskContext = contexts.get(taskId);
        if (
            ((Number) taskContext.get("attempt")).intValue() != attempt ||
            ((Number) taskContext.get("state_version")).intValue() != stateVersion ||
            !Set.of("QUEUED", "RUNNING").contains(taskContext.get("status"))
        ) {
            throw BusinessException.conflict("任务状态已变化，请重新获取任务");
        }
        var stream = storage.open((String) taskContext.get("object_key"));
        // 对象存储 I/O 期间可能被取消或撤权，返回流前再次校验，失败时关闭已打开的流。
        try {
            var currentTaskContext = contexts.get(taskId);
            if (
                ((Number) currentTaskContext.get("attempt")).intValue() != attempt ||
                ((Number) currentTaskContext.get("state_version")).intValue() != stateVersion ||
                !Set.of("QUEUED", "RUNNING").contains(currentTaskContext.get("status"))
            ) {
                throw BusinessException.conflict("任务状态已变化，请重新获取任务");
            }
        } catch (Exception failure) {
            try {
                stream.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(((Number) taskContext.get("size_bytes")).longValue())
            .header("Cache-Control", "no-store")
            .body(new InputStreamResource(stream));
    }

    /** 回传结果：幂等写入，过期 attempt/stateVersion 静默忽略 */
    @PostMapping("/{taskId}/results")
    public Map<String, String> submitResult(
        @PathVariable long taskId,
        @RequestBody ApplyTaskResultDTO request
    ) {
        taskResults.applyResult(
            taskId,
            request.attempt(),
            request.stateVersion(),
            request.success(),
            request.resultObjectKey(),
            request.errorMessage(),
            request.parsedText(),
            request.sourceSha256(),
            request.segments()
        );
        return Map.of("status", "accepted");
    }
}
