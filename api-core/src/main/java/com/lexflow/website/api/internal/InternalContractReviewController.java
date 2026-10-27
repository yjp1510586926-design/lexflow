package com.lexflow.website.api.internal;

import com.lexflow.service.ContractReviewService;
import com.lexflow.service.vo.ContractReport;
import com.lexflow.website.biz.KnowledgeSearchBiz;
import com.lexflow.website.biz.dto.ApplyContractReviewResultDTO;
import com.lexflow.website.biz.dto.BeginContractReviewExecutionDTO;
import com.lexflow.website.biz.dto.SearchContractReviewEvidenceDTO;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 服务内部回调入口；检索依据时使用本轮执行身份，结果按租户与执行版本写回。 */
@RestController
@RequestMapping("/internal/v1/contract-reviews")
public class InternalContractReviewController {

    private final ContractReviewService reviews;
    private final KnowledgeSearchBiz search;

    public InternalContractReviewController(
        ContractReviewService reviews,
        KnowledgeSearchBiz search
    ) {
        this.reviews = reviews;
        this.search = search;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/{id}/input")
    public ContractReviewService.Input input(
        @PathVariable long id,
        @RequestParam int attempt,
        @RequestParam int stateVersion
    ) {
        return reviews.input(id, attempt, stateVersion);
    }

    @PostMapping("/{id}/begin")
    public ContractReviewService.Input begin(
        @PathVariable long id,
        @RequestBody BeginContractReviewExecutionDTO request
    ) {
        return reviews.begin(id, request.attempt(), request.stateVersion());
    }

    @PostMapping("/{id}/evidence")
    public List<ContractReport.Evidence> evidence(
        @PathVariable long id,
        @RequestBody SearchContractReviewEvidenceDTO request
    ) {
        var reviewInput = reviews.input(id, request.attempt(), request.stateVersion());
        if (reviewInput.knowledgeBaseId() == null) {
            return List.of();
        }
        var actor = reviews.executionActor(id, request.attempt(), request.stateVersion());
        return search
            .search(actor, Long.parseLong(reviewInput.knowledgeBaseId()), request.query())
            .citations()
            .stream()
            .map(citation -> new ContractReport.Evidence(citation.chunkId(), citation.text()))
            .toList();
    }

    @PostMapping("/{id}/result")
    public Map<String, Boolean> result(
        @PathVariable long id,
        @RequestBody ApplyContractReviewResultDTO request
    ) {
        long tenantId;
        try {
            tenantId = Long.parseLong(request.tenantId());
        } catch (Exception ex) {
            throw new IllegalArgumentException("租户标识无效");
        }
        return Map.of(
            "accepted",
            reviews.apply(
                tenantId,
                id,
                request.attempt(),
                request.stateVersion(),
                request.outcome(),
                request.report()
            )
        );
    }
}
