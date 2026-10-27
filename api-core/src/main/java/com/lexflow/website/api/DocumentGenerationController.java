package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.DocumentGenerationService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.RecoverDocumentGenerationDTO;
import com.lexflow.website.biz.dto.SubmitDocumentGenerationDTO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 文书生成入口；请求绑定固定源版本，费用确认通过请求参数校验，结果作为草稿处理。 */
@RestController
@RequestMapping("/api/v1")
public class DocumentGenerationController {

    private final DocumentGenerationService generations;

    public DocumentGenerationController(DocumentGenerationService generations) {
        this.generations = generations;
    }

    @PostMapping("/cases/{caseId}/documents/{documentId}/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> submit(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long caseId,
        @PathVariable long documentId,
        @Valid @RequestBody SubmitDocumentGenerationDTO request
    ) {
        return ApiResponse.success(
            generations.request(
                actor,
                caseId,
                documentId,
                request.sourceVersionId(),
                request.instructions(),
                request.idempotencyKey()
            )
        );
    }

    @GetMapping("/tasks/{id}/generation")
    ApiResponse<?> view(@AuthenticationPrincipal Identity actor, @PathVariable long id) {
        return ApiResponse.success(generations.view(actor, id));
    }

    @PostMapping("/tasks/{id}/generation/recover")
    ApiResponse<?> recover(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @Valid @RequestBody RecoverDocumentGenerationDTO request
    ) {
        return ApiResponse.success(generations.recover(actor, id, request.stateVersion()));
    }
}
