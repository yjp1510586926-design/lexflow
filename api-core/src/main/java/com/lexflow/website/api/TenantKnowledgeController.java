package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.CreateTenantKnowledgeBaseDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立入口只列出律所公共库，案件材料入口保持按案件授权。 */
@RestController
@RequestMapping("/api/v1/tenant-knowledge-bases")
public class TenantKnowledgeController {

    private final KnowledgeService knowledge;

    public TenantKnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping
    public ApiResponse<?> list(
        @AuthenticationPrincipal Identity actor,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(knowledge.listTenant(actor));
    }

    @PostMapping
    public ApiResponse<?> create(
        @AuthenticationPrincipal Identity actor,
        @RequestBody CreateTenantKnowledgeBaseDTO request,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(knowledge.createTenant(actor, request.name()));
    }
}
