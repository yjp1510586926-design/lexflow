package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.TenantKnowledgeFileService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.ParseTenantKnowledgeFileDTO;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 律所公共知识库文件入口；上传临时文件在成功或失败后均删除，权限由服务层检查。 */
@RestController
@RequestMapping("/api/v1/tenant-knowledge-bases/{baseId}/files")
public class TenantKnowledgeFileController {

    private final TenantKnowledgeFileService files;

    public TenantKnowledgeFileController(TenantKnowledgeFileService files) {
        this.files = files;
    }

    @GetMapping
    ApiResponse<?> list(@AuthenticationPrincipal Identity actor, @PathVariable long baseId) {
        return ApiResponse.success(files.list(actor, baseId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<?> upload(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        if (file.isEmpty()) {
            return ApiResponse.badRequest("上传的文件不能为空");
        }
        var temporary = Files.createTempFile("lexflow-knowledge-upload-", ".tmp");
        try {
            file.transferTo(temporary);
            return ApiResponse.success(
                files.upload(
                    actor,
                    baseId,
                    temporary,
                    file.getOriginalFilename(),
                    file.getContentType()
                )
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @PostMapping("/{fileId}/parse")
    ResponseEntity<?> parse(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @PathVariable long fileId,
        @Valid @RequestBody ParseTenantKnowledgeFileDTO request
    ) {
        return ResponseEntity.accepted().body(
            ApiResponse.success(
                files.parse(
                    actor,
                    baseId,
                    fileId,
                    request.fileVersionId(),
                    request.idempotencyKey()
                )
            )
        );
    }

    @GetMapping("/{fileId}/download")
    ResponseEntity<byte[]> download(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @PathVariable long fileId
    ) throws Exception {
        var result = files.download(actor, baseId, fileId);
        String safe = result.displayName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.mimeType()))
            .contentLength(result.bytes().length)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"knowledge-file\"; filename*=UTF-8''" + encoded
            )
            .cacheControl(CacheControl.noStore())
            .body(result.bytes());
    }

    @DeleteMapping("/{fileId}")
    ApiResponse<?> delete(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long baseId,
        @PathVariable long fileId
    ) {
        files.delete(actor, baseId, fileId);
        return ApiResponse.success(null);
    }
}
