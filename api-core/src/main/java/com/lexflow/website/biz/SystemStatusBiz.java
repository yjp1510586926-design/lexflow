package com.lexflow.website.biz;

import com.lexflow.service.SystemStatusService;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 系统健康状态聚合层。
 * <p>
 * 将底层基础设施（数据库连接、对象存储可达性等）的检测结果
 * 汇总为一个可供 {@code /api/health} 端点直接返回的简单状态字符串，
 * 避免在 Controller 层直接耦合基础设施检测逻辑。
 */
@Service
public class SystemStatusBiz {

    private final SystemStatusService service;

    public SystemStatusBiz(SystemStatusService service) {
        this.service = service;
    }

    public Map<String, String> status() {
        return Map.of("application", "lexflow-core", "status", service.status());
    }
}
