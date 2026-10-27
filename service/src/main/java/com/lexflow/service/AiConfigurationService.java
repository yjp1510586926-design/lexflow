package com.lexflow.service;

import com.lexflow.dao.AiConfigurationMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.ai.AiCredentialCipher;
import com.lexflow.service.identity.Identity;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 租户级模型配置与凭据访问；公开列表由 Mapper 脱敏，明文 Key 仅交给内部调用流程。 */
@Service
public class AiConfigurationService {

    private final AiConfigurationMapper mapper;
    private final AiCredentialCipher cipher;

    public AiConfigurationService(AiConfigurationMapper mapper, AiCredentialCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    public boolean storageReady() {
        return cipher.configured();
    }

    public List<Map<String, Object>> list(long tenant) {
        return mapper.list(tenant);
    }

    public record InternalModel(String provider, String baseUrl, String model, String apiKey) {
        // 记录包含明文凭据，避免默认 record.toString() 将 API Key 带入日志。
        @Override
        public String toString() {
            return "InternalModel[redacted]";
        }
    }

    /** 仅供服务端内部任务使用；公开配置接口绝不能返回该记录。 */
    public InternalModel embeddingForTask(long tenant) {
        return modelForInternalUse(tenant, "EMBEDDING");
    }

    public InternalModel llmForQuestion(long tenant) {
        return modelForInternalUse(tenant, "LLM");
    }

    /** 排序为按需能力；未配置时明确使用向量顺序，不提供默认 Key。 */
    public InternalModel rerankForSearch(long tenant) {
        Map<String, Object> row = mapper.internal(tenant, "RERANK");
        if (row == null) {
            return null;
        }
        if (!cipher.configured()) {
            throw new BusinessException(503, "请先由部署者配置 AI 凭据加密主密钥");
        }
        return new InternalModel(
            (String) row.get("provider"),
            (String) row.get("baseUrl"),
            (String) row.get("model"),
            cipher.decrypt(tenant, "RERANK", (String) row.get("encryptedKey"))
        );
    }

    private InternalModel modelForInternalUse(long tenant, String purpose) {
        if (!cipher.configured()) {
            throw new BusinessException(503, "请先由部署者配置 AI 凭据加密主密钥");
        }
        Map<String, Object> row = mapper.internal(tenant, purpose);
        if (row == null) {
            throw BusinessException.conflict("请先由律所管理员配置 " + purpose + " API Key");
        }
        return new InternalModel(
            (String) row.get("provider"),
            (String) row.get("baseUrl"),
            (String) row.get("model"),
            cipher.decrypt(tenant, purpose, (String) row.get("encryptedKey"))
        );
    }

    public void save(
        long tenant,
        String purpose,
        String provider,
        String url,
        String model,
        String key
    ) {
        validatePurpose(purpose);
        if (!cipher.configured()) {
            throw new BusinessException(503, "请先由部署者配置 AI 凭据加密主密钥");
        }
        if (
            provider == null ||
            provider.isBlank() ||
            provider.length() > 80 ||
            model == null ||
            model.isBlank() ||
            model.length() > 120 ||
            key == null ||
            key.isBlank() ||
            key.length() > 8192
        ) {
            throw BusinessException.badRequest("请填写服务商、模型名称和 API Key");
        }
        try {
            var uri = URI.create(url);
            if (
                url.length() > 500 ||
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                uri.getUserInfo() != null ||
                uri.getQuery() != null ||
                uri.getFragment() != null
            ) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ex) {
            throw BusinessException.badRequest(
                "接口地址须为 HTTPS 地址，不能包含账号、查询参数或片段"
            );
        }
        // 凭据按租户和用途加密后保存；更改配置的修订标识由 Mapper 更新，执行流程据此判断失效。
        mapper.save(
            tenant,
            purpose,
            provider.trim(),
            url,
            model.trim(),
            cipher.encrypt(tenant, purpose, key)
        );
    }

    public int delete(long tenant, String purpose) {
        validatePurpose(purpose);
        return mapper.delete(tenant, purpose);
    }

    private void validatePurpose(String purpose) {
        if (purpose == null || !purpose.matches("LLM|EMBEDDING|RERANK")) {
            throw BusinessException.badRequest("不支持的模型用途");
        }
    }
}
