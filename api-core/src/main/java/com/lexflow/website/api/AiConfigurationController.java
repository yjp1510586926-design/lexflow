package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.AiConfigurationBiz;
import com.lexflow.website.biz.dto.SaveAiConfigurationDTO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 配置入口；权限和凭据存储由 Biz 处理，请求对象的字符串输出始终脱敏。 */
@RestController
@RequestMapping("/api/v1/settings/ai")
public class AiConfigurationController {

    private final AiConfigurationBiz biz;

    public AiConfigurationController(AiConfigurationBiz biz) {
        this.biz = biz;
    }

    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal Identity identity) {
        return ApiResponse.success(biz.list(identity));
    }

    @PutMapping("/{purpose}")
    public ApiResponse<?> save(
        @AuthenticationPrincipal Identity identity,
        @PathVariable String purpose,
        @RequestBody SaveAiConfigurationDTO request
    ) {
        biz.save(
            identity,
            purpose,
            request.provider(),
            request.baseUrl(),
            request.model(),
            request.apiKey()
        );
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{purpose}")
    public ApiResponse<?> delete(
        @AuthenticationPrincipal Identity identity,
        @PathVariable String purpose
    ) {
        biz.delete(identity, purpose);
        return ApiResponse.success(null);
    }
}
