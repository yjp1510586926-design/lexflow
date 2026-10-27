package com.lexflow.website.api.internal;

import com.lexflow.service.DocumentGenerationService;
import com.lexflow.website.biz.dto.ApplyDocumentGenerationResultDTO;
import com.lexflow.website.biz.dto.BeginDocumentGenerationExecutionDTO;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 服务内部回调入口；开始执行与结果写回都携带执行轮次和状态版本。 */
@RestController
@RequestMapping("/internal/v1/document-generations")
public class InternalDocumentGenerationController {

    private final DocumentGenerationService generations;

    public InternalDocumentGenerationController(DocumentGenerationService generations) {
        this.generations = generations;
    }

    @GetMapping("/{id}/input")
    public DocumentGenerationService.Input input(
        @PathVariable long id,
        @RequestParam int attempt,
        @RequestParam int stateVersion
    ) {
        return generations.input(id, attempt, stateVersion);
    }

    @PostMapping("/{id}/begin")
    public DocumentGenerationService.Input begin(
        @PathVariable long id,
        @RequestBody BeginDocumentGenerationExecutionDTO request
    ) {
        return generations.begin(id, request.attempt(), request.stateVersion());
    }

    @PostMapping("/{id}/result")
    public Map<String, Boolean> result(
        @PathVariable long id,
        @RequestBody ApplyDocumentGenerationResultDTO request
    ) {
        long tenantId;
        try {
            tenantId = Long.parseLong(request.tenantId());
        } catch (Exception failure) {
            throw new IllegalArgumentException("租户标识无效");
        }
        return Map.of(
            "accepted",
            generations.apply(
                tenantId,
                id,
                request.attempt(),
                request.stateVersion(),
                request.outcome(),
                request.content(),
                request.missingInformation()
            )
        );
    }
}
