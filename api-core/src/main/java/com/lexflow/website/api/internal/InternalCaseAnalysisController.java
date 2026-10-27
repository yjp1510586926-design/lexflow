package com.lexflow.website.api.internal;

import com.lexflow.service.CaseAnalysisService;
import com.lexflow.website.biz.dto.ApplyCaseAnalysisResultDTO;
import com.lexflow.website.biz.dto.BeginCaseAnalysisExecutionDTO;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 服务内部回调入口；执行轮次、状态版本和租户归属由案件分析服务校验。 */
@RestController
@RequestMapping("/internal/v1/case-analyses")
public class InternalCaseAnalysisController {

    private final CaseAnalysisService analyses;

    public InternalCaseAnalysisController(CaseAnalysisService analyses) {
        this.analyses = analyses;
    }

    @GetMapping("/{id}/input")
    public CaseAnalysisService.Input input(
        @PathVariable long id,
        @RequestParam int attempt,
        @RequestParam int stateVersion
    ) {
        return analyses.input(id, attempt, stateVersion);
    }

    @PostMapping("/{id}/begin")
    public CaseAnalysisService.Input begin(
        @PathVariable long id,
        @RequestBody BeginCaseAnalysisExecutionDTO request
    ) {
        return analyses.begin(id, request.attempt(), request.stateVersion());
    }

    @PostMapping("/{id}/result")
    public Map<String, Boolean> result(
        @PathVariable long id,
        @RequestBody ApplyCaseAnalysisResultDTO request
    ) {
        if (request.tenantId() == null || !request.tenantId().matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("租户标识无效");
        }
        return Map.of(
            "accepted",
            analyses.apply(
                Long.parseLong(request.tenantId()),
                id,
                request.attempt(),
                request.stateVersion(),
                request.outcome(),
                request.report()
            )
        );
    }
}
