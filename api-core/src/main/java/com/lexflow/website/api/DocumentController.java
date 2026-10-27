package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.DocumentService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.AddDocumentVersionDTO;
import com.lexflow.website.biz.dto.CreateDocumentDTO;
import com.lexflow.website.biz.dto.ExpectedDocumentDTO;
import com.lexflow.website.biz.dto.ReviewDocumentDTO;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文书及版本管理入口；发布和历史恢复同时校验文书版本号与当前版本标识。 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /** 列出案件文书 */
    @GetMapping("/cases/{caseId}/documents")
    ApiResponse<?> list(@AuthenticationPrincipal Identity identity, @PathVariable long caseId) {
        return ApiResponse.success(documents.listDocuments(identity, caseId));
    }

    /** 新建文书（含初始版本） */
    @PostMapping("/cases/{caseId}/documents")
    ApiResponse<?> create(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @RequestBody @Valid CreateDocumentDTO request
    ) {
        return ApiResponse.success(
            documents.create(
                identity,
                caseId,
                request.title(),
                request.content() != null ? request.content() : ""
            )
        );
    }

    /** 列出文书版本 */
    @GetMapping("/cases/{caseId}/documents/{docId}/versions")
    ApiResponse<?> versions(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @PathVariable long docId
    ) {
        return ApiResponse.success(documents.listVersions(identity, caseId, docId));
    }

    /** 追加新版本 */
    @PostMapping("/cases/{caseId}/documents/{docId}/versions")
    ApiResponse<?> addVersion(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @PathVariable long docId,
        @RequestBody @Valid AddDocumentVersionDTO request
    ) {
        return ApiResponse.success(
            documents.addVersion(
                identity,
                caseId,
                docId,
                request.content(),
                request.expectedVersion(),
                request.expectedCurrentVersionId()
            )
        );
    }

    /** 发布文书 */
    @PostMapping("/cases/{caseId}/documents/{docId}/publish")
    ApiResponse<?> publish(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @PathVariable long docId,
        @RequestBody @Valid ExpectedDocumentDTO request
    ) {
        return ApiResponse.success(
            documents.publish(
                identity,
                caseId,
                docId,
                request.expectedVersion(),
                request.expectedCurrentVersionId()
            )
        );
    }

    /** 恢复到历史版本（产生新版本） */
    @PostMapping("/cases/{caseId}/documents/{docId}/restore/{fromVersionId}")
    ApiResponse<?> restore(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @PathVariable long docId,
        @PathVariable long fromVersionId,
        @RequestBody @Valid ExpectedDocumentDTO request
    ) {
        return ApiResponse.success(
            documents.restoreVersion(
                identity,
                caseId,
                docId,
                fromVersionId,
                request.expectedVersion(),
                request.expectedCurrentVersionId()
            )
        );
    }

    /** 人工复核 AI 生成的草稿版本（通过/驳回） */
    @PostMapping("/cases/{caseId}/documents/{docId}/versions/{versionId}/review")
    ApiResponse<?> review(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @PathVariable long docId,
        @PathVariable long versionId,
        @RequestBody @Valid ReviewDocumentDTO request
    ) {
        return ApiResponse.success(
            documents.reviewVersion(identity, caseId, docId, versionId, request.status())
        );
    }

    /** 导出当前已保存版本，草稿不被描述为正式结论。 */
    @GetMapping("/cases/{caseId}/documents/{docId}/export")
    ResponseEntity<byte[]> export(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @PathVariable long docId,
        @RequestParam(required = false) Long expectedVersionId
    ) {
        var data = documents.getExportData(identity, caseId, docId, expectedVersionId);
        byte[] bytes = data.content().getBytes(StandardCharsets.UTF_8);
        return DocumentDownloadHeaders.response(
            data.title(),
            data.versionNo(),
            data.status(),
            bytes
        );
    }
}
