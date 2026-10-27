package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.ContractReviewService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.ApproveContractReviewDTO;
import com.lexflow.website.biz.dto.RecoverContractReviewDTO;
import com.lexflow.website.biz.dto.SubmitContractReviewDTO;
import com.lexflow.website.biz.dto.SupplementContractReviewDTO;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 合同审查流程入口；审批、补充和恢复均携带当前状态版本，避免处理过期结果。 */
@RestController
@RequestMapping("/api/v1/contract-reviews")
public class ContractReviewController {

    private final ContractReviewService reviews;

    public ContractReviewController(ContractReviewService reviews) {
        this.reviews = reviews;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> submit(
        @AuthenticationPrincipal Identity actor,
        @RequestBody SubmitContractReviewDTO request
    ) {
        return ApiResponse.success(
            reviews.request(
                actor,
                request.caseId(),
                request.fileVersionId(),
                request.stance(),
                request.focus(),
                request.knowledgeBaseId(),
                request.idempotencyKey()
            )
        );
    }

    @GetMapping("/{id}")
    ApiResponse<?> get(@AuthenticationPrincipal Identity actor, @PathVariable long id) {
        return ApiResponse.success(reviews.view(actor, id));
    }

    @PostMapping("/{id}/approve")
    ApiResponse<?> approve(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @RequestBody ApproveContractReviewDTO request
    ) {
        return ApiResponse.success(
            reviews.approve(actor, id, request.stateVersion(), request.decisionId(), request.note())
        );
    }

    @PostMapping("/{id}/recover")
    ApiResponse<?> recover(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @RequestBody RecoverContractReviewDTO request
    ) {
        return ApiResponse.success(reviews.recover(actor, id, request.stateVersion()));
    }

    @GetMapping("/{id}/supplement-candidates")
    ApiResponse<?> candidates(@AuthenticationPrincipal Identity actor, @PathVariable long id) {
        return ApiResponse.success(reviews.candidates(actor, id));
    }

    @PostMapping("/{id}/supplement")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> supplement(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @RequestBody SupplementContractReviewDTO request
    ) {
        return ApiResponse.success(
            reviews.supplement(
                actor,
                id,
                request.stateVersion(),
                request.requestId(),
                request.parseTaskIds(),
                request.instructions(),
                request.costConfirmed()
            )
        );
    }

    @GetMapping("/{id}/citations/{chunkId}")
    ApiResponse<?> citation(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @PathVariable long chunkId
    ) {
        return ApiResponse.success(reviews.citation(actor, id, chunkId));
    }

    @GetMapping("/{id}/citations/{chunkId}/download")
    ResponseEntity<?> download(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @PathVariable long chunkId
    ) throws Exception {
        var result = reviews.downloadCitation(actor, id, chunkId);
        var name = URLEncoder.encode(result.displayName(), StandardCharsets.UTF_8).replace(
            "+",
            "%20"
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(result.mimeType()))
            .contentLength(result.sizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
            .body(new InputStreamResource(result.stream()));
    }
}
