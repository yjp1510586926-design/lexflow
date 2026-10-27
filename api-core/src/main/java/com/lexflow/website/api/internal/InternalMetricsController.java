package com.lexflow.website.api.internal;

import com.lexflow.service.OperationalMetricsService;
import com.lexflow.service.audit.AuditOfflineBuffer;
import com.lexflow.service.audit.AuditOperationalMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维指标由内部共享密钥保护，返回实例级统计，不按浏览器会话或租户筛选。 */
@RestController
public class InternalMetricsController {

    private final AuditOfflineBuffer buffer;
    private final AuditOperationalMetrics audits;
    private final OperationalMetricsService operations;

    public InternalMetricsController(
        AuditOfflineBuffer buffer,
        AuditOperationalMetrics audits,
        OperationalMetricsService operations
    ) {
        this.buffer = buffer;
        this.audits = audits;
        this.operations = operations;
    }

    @GetMapping(value = "/internal/v1/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .body(audits.render(buffer.snapshot()) + operations.render());
    }
}
