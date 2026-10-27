package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.EvidenceService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.SaveEvidenceDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 案件证据说明入口；请求保留证据版本号，用于检测并发更新。 */
@RestController
@Validated
@RequestMapping("/api/v1/cases/{caseId}/evidence")
public class EvidenceController {

    private final EvidenceService evidence;

    public EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    ApiResponse<?> list(
        @AuthenticationPrincipal Identity actor,
        @PathVariable @Positive long caseId,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(evidence.list(actor, caseId, page, size));
    }

    @GetMapping("/{fileVersionId}")
    ApiResponse<?> get(
        @AuthenticationPrincipal Identity actor,
        @PathVariable @Positive long caseId,
        @PathVariable @Positive long fileVersionId
    ) {
        return ApiResponse.success(evidence.get(actor, caseId, fileVersionId));
    }

    @PutMapping("/{fileVersionId}")
    ApiResponse<?> save(
        @AuthenticationPrincipal Identity actor,
        @PathVariable @Positive long caseId,
        @PathVariable @Positive long fileVersionId,
        @Valid @RequestBody SaveEvidenceDTO request
    ) {
        return ApiResponse.success(
            evidence.save(
                actor,
                caseId,
                fileVersionId,
                request.version(),
                request.source(),
                request.collectedAt(),
                request.description(),
                request.sortOrder()
            )
        );
    }
}
