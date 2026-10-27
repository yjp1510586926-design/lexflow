package com.lexflow.website.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService.InternalModel;
import com.lexflow.service.AiConversationService.Evidence;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 调用内部资料问答接口，并校验回答与引用格式，供问答业务层持久化结果。 */
@Component
public class LegalQuestionClient {

    public record Answer(String answer, List<String> citationIds, boolean insufficientEvidence) {}

    private final ObjectMapper json;
    private final URI endpoint;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public LegalQuestionClient(
        ObjectMapper json,
        @Value("${lexflow.ai-service-url}") String aiUrl,
        @Value("${lexflow.internal-secret}") String secret
    ) {
        this.json = json;
        this.secret = secret;
        URI uri = InternalAiServiceUrl.parse(aiUrl);
        endpoint = uri.resolve("/internal/v1/ai/answer");
    }

    public Answer answer(
        InternalModel model,
        String question,
        List<String> previousQuestions,
        List<Evidence> evidence
    ) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("provider", model.provider());
        requestBody.put("baseUrl", model.baseUrl());
        requestBody.put("model", model.model());
        requestBody.put("apiKey", model.apiKey());
        requestBody.put("question", question);
        requestBody.put("previousQuestions", previousQuestions);
        requestBody.put(
            "evidence",
            evidence
                .stream()
                .map(item -> Map.of("id", item.id(), "text", item.text()))
                .toList()
        );
        try {
            var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(70))
                .header("X-Internal-Secret", secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody)))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 100_000) {
                throw BusinessException.conflict("资料问答暂不可用，请检查模型配置或稍后重试");
            }
            var answerNode = json.readTree(response.body());
            if (
                !answerNode.path("answer").isTextual() ||
                !answerNode.path("insufficientEvidence").isBoolean() ||
                !answerNode.path("citationIds").isArray() ||
                answerNode.path("citationIds").size() > 10
            ) {
                throw BusinessException.conflict("模型回答格式无效，请重新提问");
            }
            List<String> citationIds = new ArrayList<>();
            for (var citationId : answerNode.path("citationIds")) {
                if (!citationId.isTextual()) {
                    throw BusinessException.conflict("模型引用无效，请重新提问");
                }
                citationIds.add(citationId.asText());
            }
            return new Answer(
                answerNode.path("answer").asText(),
                citationIds,
                answerNode.path("insufficientEvidence").asBoolean()
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BusinessException.conflict("资料问答已中断，请刷新记录");
        } catch (IOException ex) {
            throw BusinessException.conflict("资料问答暂不可用，请稍后重试");
        }
    }
}
