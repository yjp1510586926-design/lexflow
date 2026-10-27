package com.lexflow.website.api.internal;

import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.website.biz.dto.CompleteKnowledgeIndexDTO;
import com.lexflow.website.biz.dto.RegisterKnowledgeVectorsDTO;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部接口由共享密钥过滤器保护；索引输入含模型凭据，仅供 AI 服务执行时使用。 */
@RestController
@RequestMapping("/internal/v1/knowledge-index")
public class InternalKnowledgeIndexController {

    private final KnowledgeIndexService indexes;

    public InternalKnowledgeIndexController(KnowledgeIndexService indexes) {
        this.indexes = indexes;
    }

    @GetMapping("/{taskId}/input")
    public ResponseEntity<KnowledgeIndexService.IndexInput> input(
        @PathVariable long taskId,
        @RequestParam int attempt,
        @RequestParam int stateVersion
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(indexes.input(taskId, attempt, stateVersion));
    }

    @PostMapping("/{taskId}/vectors")
    public Map<String, Object> vectors(
        @PathVariable long taskId,
        @RequestBody RegisterKnowledgeVectorsDTO request
    ) {
        indexes.registerVectors(
            taskId,
            request.attempt(),
            request.stateVersion(),
            request.collectionName(),
            request.dimension(),
            request.modelIdentity()
        );
        return Map.of("registered", true);
    }

    @PostMapping("/{taskId}/result")
    public Map<String, Object> result(
        @PathVariable long taskId,
        @RequestBody CompleteKnowledgeIndexDTO request
    ) {
        return Map.of(
            "accepted",
            indexes.complete(
                taskId,
                request.attempt(),
                request.stateVersion(),
                request.success(),
                request.collectionName(),
                request.dimension(),
                request.indexedChunks(),
                request.modelIdentity()
            )
        );
    }
}
