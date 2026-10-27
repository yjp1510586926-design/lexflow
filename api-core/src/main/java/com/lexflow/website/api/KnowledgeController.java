package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.KnowledgeSearchBiz;
import com.lexflow.website.biz.dto.CreateKnowledgeBaseDTO;
import com.lexflow.website.biz.dto.PrepareKnowledgeDocumentDTO;
import com.lexflow.website.biz.dto.SearchKnowledgeDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 案件知识库入口；片段准备和向量索引分别执行，检索返回带出处的原文片段。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final KnowledgeIndexService indexes;
    private final KnowledgeSearchBiz searches;

    public KnowledgeController(
        KnowledgeService knowledge,
        KnowledgeIndexService indexes,
        KnowledgeSearchBiz searches
    ) {
        this.knowledge = knowledge;
        this.indexes = indexes;
        this.searches = searches;
    }

    @PostMapping
    ApiResponse<?> create(
        @AuthenticationPrincipal Identity actor,
        @Valid @RequestBody CreateKnowledgeBaseDTO request
    ) {
        return ApiResponse.success(knowledge.create(actor, request.caseId(), request.name()));
    }

    @GetMapping
    ApiResponse<?> list(
        @AuthenticationPrincipal Identity actor,
        @RequestParam @Positive long caseId
    ) {
        return ApiResponse.success(knowledge.list(actor, caseId));
    }

    @GetMapping("/{baseId}")
    ApiResponse<?> get(@AuthenticationPrincipal Identity actor, @PathVariable long baseId) {
        return ApiResponse.success(knowledge.get(actor, baseId));
    }

    /** 仅准备文档片段；向量索引发布前保持 PREPARED，避免把准备完成误判为可检索。 */
    @PostMapping("/{baseId}/documents")
    ApiResponse<?> prepare(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @Valid @RequestBody PrepareKnowledgeDocumentDTO request
    ) {
        return ApiResponse.success(knowledge.prepare(actor, baseId, request.parseTaskId()));
    }

    @GetMapping("/{baseId}/documents")
    ApiResponse<?> documents(@AuthenticationPrincipal Identity actor, @PathVariable long baseId) {
        return ApiResponse.success(knowledge.documents(actor, baseId));
    }

    @GetMapping("/{baseId}/documents/{documentId}/chunks")
    ApiResponse<?> chunks(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @PathVariable long documentId
    ) {
        return ApiResponse.success(knowledge.chunks(actor, baseId, documentId));
    }

    @PostMapping("/{baseId}/documents/{documentId}/index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> index(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @PathVariable long documentId
    ) {
        return ApiResponse.success(indexes.request(actor, baseId, documentId));
    }

    /** 返回带出处的原文片段，法律问答由会话接口另行编排。 */
    @PostMapping("/{baseId}/search")
    ApiResponse<?> search(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @Valid @RequestBody SearchKnowledgeDTO request
    ) {
        return ApiResponse.success(searches.search(actor, baseId, request.question()));
    }
}
