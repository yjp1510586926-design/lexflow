package com.lexflow.website.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationTestService;
import com.lexflow.service.AiConfigurationTestService.Claim;
import com.lexflow.service.AiConfigurationTestService.Outcome;
import com.lexflow.service.AiConfigurationTestService.View;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.core.client.InternalAiServiceUrl;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 编排模型配置探测；登记、领取和保存结果使用短事务，外部请求期间不持有事务。 */
@Service
public class AiConfigurationTestBiz {

    private final AiConfigurationTestService tests;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public AiConfigurationTestBiz(
        AiConfigurationTestService tests,
        ObjectMapper json,
        @Value("${lexflow.ai-service-url}") String url,
        @Value("${lexflow.internal-secret}") String secret
    ) {
        this.tests = tests;
        this.json = json;
        this.secret = secret;
        URI base = InternalAiServiceUrl.parse(url);
        endpoint = base.resolve("/internal/v1/ai/configuration-test");
    }

    /** 请求标识绑定配置修订；重复请求可读取结果，但不能再次领取已开始的探测。 */
    public View test(Identity actor, String purpose, String revision, String requestId) {
        View prepared;
        try {
            prepared = tests.prepare(actor, purpose, revision, requestId);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.conflict("请求标识发生冲突，请读取最新测试结果");
        }
        Claim claim = tests.begin(actor, Long.parseLong(prepared.id()));
        if (claim != null) {
            tests.finish(claim, remote(claim));
        }
        return tests.get(actor, Long.parseLong(prepared.id()));
    }

    public View latest(Identity actor, String purpose, String revision) {
        return tests.latest(actor, purpose, revision);
    }

    public View get(Identity actor, long id) {
        return tests.get(actor, id);
    }

    /** 通信失败无法证明供应商未执行，因此保存 UNKNOWN，不自动重发可能收费的探测。 */
    private Outcome remote(Claim claim) {
        long started = System.nanoTime();
        try {
            var model = claim.model();
            String body = json.writeValueAsString(
                Map.of(
                    "tenantId",
                    Long.toString(claim.actor().tenantId()),
                    "testId",
                    Long.toString(claim.id()),
                    "purpose",
                    claim.purpose(),
                    "revision",
                    claim.revision(),
                    "provider",
                    model.provider(),
                    "baseUrl",
                    model.baseUrl(),
                    "model",
                    model.model(),
                    "apiKey",
                    model.apiKey()
                )
            );
            var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .header("X-Internal-Secret", secret)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            // 独立总超时覆盖响应头已到但响应体停滞的情况。超时后取消传输，不重发。
            var pending = http.sendAsync(request, ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(45, TimeUnit.SECONDS);
            } catch (Exception ex) {
                pending.cancel(true);
                throw ex;
            }
            byte[] bytes = response.body();
            if (response.statusCode() != 200 || bytes.length > 4096) {
                return unknown(started);
            }
            {
                JsonNode result = json.readTree(bytes);
                if (
                    !result.isObject() ||
                    !result.path("status").isTextual() ||
                    !result.path("elapsedMs").isIntegralNumber() ||
                    !result.path("elapsedMs").canConvertToInt()
                ) {
                    return unknown(started);
                }
                String status = result.path("status").asText();
                int elapsed = result.path("elapsedMs").asInt();
                if (
                    !status.matches("SUCCEEDED|FAILED|UNKNOWN|PROCESSING") ||
                    elapsed < 0 ||
                    elapsed > 120_000
                ) {
                    return unknown(started);
                }
                Integer dimension = null;
                if ("SUCCEEDED".equals(status) && "EMBEDDING".equals(claim.purpose())) {
                    var d = result.path("dimension");
                    if (
                        !d.isIntegralNumber() ||
                        !d.canConvertToInt() ||
                        d.asInt() < 2 ||
                        d.asInt() > 4096
                    ) {
                        return unknown(started);
                    }
                    dimension = d.asInt();
                }
                // 供应商正文和可变错误文案不进入持久化或公开响应。
                return new Outcome(
                    status,
                    elapsed,
                    dimension,
                    "SUCCEEDED".equals(status)
                        ? null
                        : "FAILED".equals(status)
                          ? "PROBE_FAILED"
                          : "RESPONSE_UNKNOWN"
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return unknown(started);
        } catch (Exception ex) {
            return unknown(started);
        }
    }

    /** 在流式读取阶段限制响应大小，避免先缓冲完整响应再进行大小校验。 */
    private static final class LimitedBodySubscriber
        implements HttpResponse.BodySubscriber<byte[]>
    {

        private final HttpResponse.BodySubscriber<byte[]> delegate =
            HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int count;

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                count += buffer.remaining();
            }
            if (count > 4096) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("Test response too large"));
            } else {
                delegate.onNext(buffers);
            }
        }

        public void onError(Throwable error) {
            delegate.onError(error);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }

    private Outcome unknown(long started) {
        return new Outcome(
            "UNKNOWN",
            (int) Math.min(120_000, (System.nanoTime() - started) / 1_000_000),
            null,
            "RESPONSE_UNKNOWN"
        );
    }
}
