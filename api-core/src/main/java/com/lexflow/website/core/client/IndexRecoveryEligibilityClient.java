package com.lexflow.website.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.service.KnowledgeIndexService.IndexInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只读探测原索引执行是否可恢复；发送不可变输入标识，不传运行时模型凭据。 */
@Component
public class IndexRecoveryEligibilityClient {

    private final ObjectMapper json;
    private final RecoveryEligibilityHttpClient http;

    public IndexRecoveryEligibilityClient(
        ObjectMapper json,
        @Value("${lexflow.ai-service-url}") String url,
        @Value("${lexflow.internal-secret}") String secret
    ) {
        this.json = json;
        this.http = new RecoveryEligibilityHttpClient(
            json,
            url,
            secret,
            "/internal/v1/index-recovery/eligibility"
        );
    }

    public String check(long taskId, IndexInput input) {
        // 在序列化副本上剔除 API Key，不修改后续正常执行仍需使用的原始输入。
        var sanitized = json.valueToTree(input);
        ((ObjectNode) sanitized).remove("apiKey");
        var body = json.createObjectNode().put("taskId", Long.toString(taskId));
        body.set("input", sanitized);
        return http.check(body);
    }
}
