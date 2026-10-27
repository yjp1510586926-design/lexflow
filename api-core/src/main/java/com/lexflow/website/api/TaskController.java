package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.SubmitTaskDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 异步任务入口；提交、取消及结果读取均经过服务层的租户与资源权限校验。 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    /** 提交异步任务（Outbox 事务模式） */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> submit(
        @AuthenticationPrincipal Identity identity,
        @Valid @RequestBody SubmitTaskDTO request
    ) {
        return ApiResponse.success(
            tasks.submitTask(
                identity,
                request.caseId(),
                request.type(),
                request.resourceVersionId(),
                request.idempotencyKey()
            )
        );
    }

    /** 任务列表（分页，可按 caseId/type/status 过滤） */
    @GetMapping
    ApiResponse<?> list(
        @AuthenticationPrincipal Identity identity,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(required = false) Long caseId,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.success(tasks.list(identity, page, size, caseId, type, status));
    }

    /** 任务详情 */
    @GetMapping("/{taskId}")
    ApiResponse<?> get(@AuthenticationPrincipal Identity identity, @PathVariable long taskId) {
        return ApiResponse.success(tasks.get(identity, taskId));
    }

    @GetMapping("/{taskId}/parsed-text")
    ApiResponse<?> parsedText(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long taskId
    ) {
        return ApiResponse.success(tasks.parsedText(identity, taskId));
    }

    /** 请求取消（尽力执行，终态拒绝） */
    @PostMapping("/{taskId}/cancel")
    ApiResponse<?> cancel(@AuthenticationPrincipal Identity identity, @PathVariable long taskId) {
        return ApiResponse.success(tasks.requestCancel(identity, taskId));
    }
}
