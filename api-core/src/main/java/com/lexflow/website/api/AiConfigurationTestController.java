package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.AiConfigurationTestBiz;
import com.lexflow.website.biz.dto.TestAiConfigurationDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 连接测试入口；测试绑定配置修订，结果禁止缓存以避免返回过期连接状态。 */
@RestController
@RequestMapping("/api/v1/settings/ai")
public class AiConfigurationTestController {

    private final AiConfigurationTestBiz biz;

    public AiConfigurationTestController(AiConfigurationTestBiz biz) {
        this.biz = biz;
    }

    @PostMapping("/{purpose}/tests")
    public ApiResponse<?> test(
        @AuthenticationPrincipal Identity actor,
        @PathVariable String purpose,
        @RequestBody TestAiConfigurationDTO request,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(
            biz.test(actor, purpose, request.revision(), request.requestId())
        );
    }

    @GetMapping("/{purpose}/tests/latest")
    public ApiResponse<?> latest(
        @AuthenticationPrincipal Identity actor,
        @PathVariable String purpose,
        @RequestParam String revision,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(biz.latest(actor, purpose, revision));
    }

    @GetMapping("/tests/{id}")
    public ApiResponse<?> get(
        @AuthenticationPrincipal Identity actor,
        @PathVariable long id,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(biz.get(actor, id));
    }
}
