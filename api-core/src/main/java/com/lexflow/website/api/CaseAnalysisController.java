package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.CaseAnalysisService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.ApproveCaseAnalysisDTO;
import com.lexflow.website.biz.dto.RecoverCaseAnalysisDTO;
import com.lexflow.website.biz.dto.SubmitCaseAnalysisDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 案件分析流程入口；提交、审批和恢复由服务层校验权限、执行状态与幂等条件。 */
@RestController
@RequestMapping("/api/v1/case-analyses")
public class CaseAnalysisController {

    private final CaseAnalysisService analyses;

    public CaseAnalysisController(CaseAnalysisService analyses) {
        this.analyses = analyses;
    }

    @GetMapping("/sources")
    public ResponseEntity<?> sources(
        @AuthenticationPrincipal Identity actor,
        @RequestParam long caseId
    ) {
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(ApiResponse.success(analyses.sources(actor, caseId)));
    }

    @PostMapping
    public ResponseEntity<?> submit(
        @AuthenticationPrincipal Identity actor,
        @RequestBody SubmitCaseAnalysisDTO request
    ) {
        return ResponseEntity.accepted()
            .header("Cache-Control", "no-store")
            .body(
                ApiResponse.success(
                    analyses.request(
                        actor,
                        request.caseId(),
                        request.parseTaskIds(),
                        request.background(),
                        request.requirements(),
                        request.idempotencyKey(),
                        request.confirmModelCosts()
                    )
                )
            );
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Identity actor, @PathVariable long id) {
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(ApiResponse.success(analyses.view(actor, id)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @RequestBody ApproveCaseAnalysisDTO request
    ) {
        return ResponseEntity.accepted()
            .header("Cache-Control", "no-store")
            .body(
                ApiResponse.success(
                    analyses.approve(
                        actor,
                        id,
                        request.stateVersion(),
                        request.decisionId(),
                        request.note()
                    )
                )
            );
    }

    @PostMapping("/{id}/recover")
    public ResponseEntity<?> recover(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @RequestBody RecoverCaseAnalysisDTO request
    ) {
        return ResponseEntity.accepted()
            .header("Cache-Control", "no-store")
            .body(ApiResponse.success(analyses.recover(actor, id, request.stateVersion())));
    }
}
