package com.lexflow.website.core.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** 恢复资格检查的内部 HTTP 客户端：限制时长及响应大小，不记录载荷或凭据。 */
final class RecoveryEligibilityHttpClient {

    private static final int MAX_RESPONSE_BYTES = 1024;
    private static final Set<String> STATUSES = Set.of(
        "SAFE",
        "BUSY",
        "UNCERTAIN",
        "FAILED",
        "INPUT_CHANGED",
        "UNAVAILABLE"
    );
    private final ObjectMapper json;
    private final URI endpoint;
    private final String secret;
    // 共享密钥只发往配置的内部地址，禁止跟随重定向把密钥转交给其他站点。
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    RecoveryEligibilityHttpClient(ObjectMapper json, String url, String secret, String path) {
        this.json = json;
        this.secret = secret;
        URI uri = InternalAiServiceUrl.parse(url);
        endpoint = uri.resolve(path);
    }

    String check(JsonNode body) {
        var subscriber = new BoundedBodySubscriber();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("X-Internal-Secret", secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
            pending = http.sendAsync(request, ignored -> subscriber);
            // 超时覆盖整个响应体，防止对端只发送响应头后一直不结束。
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                return "UNAVAILABLE";
            }
            var value = json
                .reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readTree(response.body());
            if (
                value == null ||
                !value.isObject() ||
                value.size() != 1 ||
                !value.path("status").isTextual()
            ) {
                return "UNAVAILABLE";
            }
            String status = value.path("status").textValue();
            return STATUSES.contains(status) ? status : "UNAVAILABLE";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "UNAVAILABLE";
        } catch (
            IOException
            | IllegalArgumentException
            | ExecutionException
            | TimeoutException
            | CancellationException ex
        ) {
            return "UNAVAILABLE";
        } finally {
            subscriber.abort();
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    /** 复制前检查剩余字节额度，并处理订阅前后的取消，避免无界缓存内部服务响应。 */
    private static final class BoundedBodySubscriber
        implements HttpResponse.BodySubscriber<byte[]>
    {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_RESPONSE_BYTES);

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value) || body.isDone()) {
                value.cancel();
                return;
            }
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (var buffer : buffers) {
                int length = buffer.remaining();
                if (length > MAX_RESPONSE_BYTES - bytes.size()) {
                    abort();
                    return;
                }
                byte[] part = new byte[length];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            var current = subscription.get();
            if (current != null && !body.isDone()) {
                current.request(1);
            }
        }

        @Override
        public void onError(Throwable ignored) {
            abort();
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }

        private void abort() {
            if (body.completeExceptionally(new IOException("任务恢复检查结果不可用"))) {
                var current = subscription.get();
                if (current != null) {
                    current.cancel();
                }
            }
        }
    }
}
