package com.lexflow.website.biz.dto;

/** AI 配置保存参数；toString 始终脱敏，避免日志泄露凭据。 */
public record SaveAiConfigurationDTO(String provider, String baseUrl, String model, String apiKey) {
    @Override
    public String toString() {
        return "AiConfigurationSave[redacted]";
    }
}
