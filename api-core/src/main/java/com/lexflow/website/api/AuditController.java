package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.AuditService;
import com.lexflow.service.identity.Identity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志查询入口；服务层根据会话身份限制租户范围并校验查询权限。 */
@RestController
@Validated
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    private final AuditService audits;

    public AuditController(AuditService audits) {
        this.audits = audits;
    }

    @GetMapping
    ApiResponse<?> list(
        @AuthenticationPrincipal Identity identity,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(required = false) @Size(max = 64) String action
    ) {
        return ApiResponse.success(audits.list(identity, page, size, action));
    }
}
