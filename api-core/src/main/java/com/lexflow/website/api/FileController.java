package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.FileService;
import com.lexflow.service.FileService.DownloadResult;
import com.lexflow.service.identity.Identity;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 案件文件入口；HTTP 层负责临时文件和下载响应，文件权限与存储操作交由服务层。 */
@RestController
@RequestMapping("/api/v1")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    /** 列出案件文件 */
    @GetMapping("/cases/{caseId}/files")
    ApiResponse<?> list(@AuthenticationPrincipal Identity identity, @PathVariable long caseId) {
        return ApiResponse.success(files.listFiles(identity, caseId));
    }

    /** multipart 上传，限制由 UploadInspector 及 Spring 配置决定 */
    @PostMapping(value = "/cases/{caseId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<?> upload(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long caseId,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        if (file.isEmpty()) {
            return ApiResponse.badRequest("上传的文件不能为空");
        }
        // HTTP 上传先落临时文件，服务层使用文件路径处理；finally 负责释放临时资源。
        Path temporaryFile = Files.createTempFile("lexflow-upload-", ".tmp");
        try {
            file.transferTo(temporaryFile);
            return ApiResponse.success(
                files.upload(
                    identity,
                    caseId,
                    temporaryFile,
                    file.getOriginalFilename(),
                    file.getContentType()
                )
            );
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /** 代理下载（权限在 FileService 内再次校验） */
    @GetMapping("/files/{fileId}/download")
    ResponseEntity<?> download(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long fileId
    ) throws Exception {
        DownloadResult result = files.download(identity, fileId);

        // RFC 5987 编码文件名，兼容中文
        String encodedName = URLEncoder.encode(
            result.displayName(),
            StandardCharsets.UTF_8
        ).replace("+", "%20");
        String contentDisposition = "attachment; filename*=UTF-8''" + encodedName;

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.mimeType()))
            .contentLength(result.sizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
            .body(new InputStreamResource(result.stream()));
    }

    /** 逻辑删除文件 */
    @DeleteMapping("/files/{fileId}")
    ApiResponse<?> delete(@AuthenticationPrincipal Identity identity, @PathVariable long fileId)
        throws Exception {
        files.delete(identity, fileId);
        return ApiResponse.success(null);
    }
}
