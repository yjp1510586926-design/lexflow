package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.website.biz.SystemStatusBiz;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探针入口，供容器编排（K8s liveness/readiness）或监控平台周期性检查服务存活状态。
 * <p>
 * 该端点在 {@link com.lexflow.website.core.config.SecurityConfig} 中被列为白名单，
 * 无需认证即可访问。
 */
@RestController
public class SystemStatusController {

    private final SystemStatusBiz biz;

    public SystemStatusController(SystemStatusBiz biz) {
        this.biz = biz;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(biz.status());
    }
}
