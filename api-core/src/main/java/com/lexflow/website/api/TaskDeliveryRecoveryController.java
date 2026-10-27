package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import com.lexflow.website.biz.dto.RecoverTaskDeliveryDTO;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务投递恢复入口；状态版本与请求标识共同保护恢复流程，模型费用确认由 Biz 校验。 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/delivery-recovery")
public class TaskDeliveryRecoveryController {

    private final TaskDeliveryRecoveryBiz recovery;

    public TaskDeliveryRecoveryController(TaskDeliveryRecoveryBiz recovery) {
        this.recovery = recovery;
    }

    @GetMapping
    ResponseEntity<?> view(@AuthenticationPrincipal Identity actor, @PathVariable long taskId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(recovery.view(actor, taskId)));
    }

    @PostMapping("/recover")
    ResponseEntity<?> recover(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long taskId,
        @Valid @RequestBody RecoverTaskDeliveryDTO request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .cacheControl(CacheControl.noStore())
            .body(
                ApiResponse.success(
                    recovery.recover(
                        actor,
                        taskId,
                        request.stateVersion(),
                        request.requestId(),
                        request.confirmModelCosts()
                    )
                )
            );
    }
}
