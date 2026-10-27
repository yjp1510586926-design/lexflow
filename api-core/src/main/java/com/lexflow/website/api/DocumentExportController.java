package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.DocumentExportService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.DocumentExportBiz;
import com.lexflow.website.biz.dto.CreateDocumentExportDTO;
import com.lexflow.website.biz.dto.RecoverDocumentExportDTO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 文书导出入口；任务绑定固定版本，下载时先重新授权，再由 Biz 获取导出文件。 */
@RestController
@RequestMapping("/api/v1")
public class DocumentExportController {

    private final DocumentExportService exports;
    private final DocumentExportBiz biz;

    public DocumentExportController(DocumentExportService exports, DocumentExportBiz biz) {
        this.exports = exports;
        this.biz = biz;
    }

    @PostMapping("/cases/{caseId}/documents/{documentId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> request(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long caseId,
        @PathVariable long documentId,
        @Valid @RequestBody CreateDocumentExportDTO request
    ) {
        return ApiResponse.success(
            exports.request(
                actor,
                caseId,
                documentId,
                request.expectedVersionId(),
                request.idempotencyKey()
            )
        );
    }

    @GetMapping("/tasks/{id}/export")
    ApiResponse<?> view(@AuthenticationPrincipal Identity actor, @PathVariable long id) {
        return ApiResponse.success(exports.view(actor, id));
    }

    @PostMapping("/tasks/{id}/export/recover")
    ApiResponse<?> recover(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        @Valid @RequestBody RecoverDocumentExportDTO request
    ) {
        return ApiResponse.success(exports.recover(actor, id, request.stateVersion()));
    }

    @GetMapping("/tasks/{id}/export/download")
    ResponseEntity<byte[]> download(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id
    ) {
        var metadata = exports.download(actor, id);
        byte[] bytes = biz.download(actor, id);
        return DocumentDownloadHeaders.response(
            metadata.title(),
            metadata.versionNo(),
            metadata.status(),
            bytes
        );
    }
}
