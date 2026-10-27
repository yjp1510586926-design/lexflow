package com.lexflow.website.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只读探测原付费工作流的恢复资格，不发起新的模型执行，也不传运行时 API Key。 */
@Component
public class WorkflowRecoveryEligibilityClient {

    private final ObjectMapper json;
    private final RecoveryEligibilityHttpClient http;

    public WorkflowRecoveryEligibilityClient(
        ObjectMapper json,
        @Value("${lexflow.ai-service-url}") String url,
        @Value("${lexflow.internal-secret}") String secret
    ) {
        this.json = json;
        this.http = new RecoveryEligibilityHttpClient(
            json,
            url,
            secret,
            "/internal/v1/workflow-recovery/eligibility"
        );
    }

    public String check(String type, long taskId, Object input, List<?> verifiedEvidence) {
        if (!Set.of("CONTRACT_REVIEW", "DOCUMENT_GENERATE", "CASE_ANALYSIS").contains(type)) {
            return "UNAVAILABLE";
        }
        ObjectNode sanitized = json.valueToTree(input);
        sanitized.remove("apiKey");
        var body = json
            .createObjectNode()
            .put("type", type)
            .put("taskId", Long.toString(taskId))
            .put("executionProtocol", 2);
        body.set("input", sanitized);
        // 合同审查依据须由调用方先复核；探测端使用这些已验证材料判断原执行是否匹配。
        if ("CONTRACT_REVIEW".equals(type) && verifiedEvidence != null) {
            body.set("verifiedEvidence", json.valueToTree(verifiedEvidence));
        }
        return http.check(body);
    }
}
