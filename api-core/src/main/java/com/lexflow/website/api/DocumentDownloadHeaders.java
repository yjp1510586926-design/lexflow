package com.lexflow.website.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 文书下载响应保持版本与审核状态元数据，并统一清理、编码中文文件名。 */
final class DocumentDownloadHeaders {

    private DocumentDownloadHeaders() {}

    static ResponseEntity<byte[]> response(String title, int version, String status, byte[] bytes) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safe.isEmpty()) {
            safe = "文书";
        }
        safe = safe.substring(
            0,
            safe.offsetByCodePoints(0, Math.min(100, safe.codePointCount(0, safe.length())))
        );
        String name = URLEncoder.encode(
            safe + "_v" + version + ".txt",
            StandardCharsets.UTF_8
        ).replace("+", "%20");
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"document_v" + version + ".txt\"; filename*=UTF-8''" + name
            )
            .header("X-Document-Version", Integer.toString(version))
            .header("X-Document-Status", status)
            .cacheControl(CacheControl.noStore())
            .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .contentLength(bytes.length)
            .body(bytes);
    }
}
