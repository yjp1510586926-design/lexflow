package com.lexflow.website.core.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.KnowledgeVectorArtifactRepository.Artifact;
import com.lexflow.website.core.client.InternalAiServiceUrl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 限制内部回收请求的耗时与响应体大小；删除幂等，响应不确定时可安全重试。 */
@Component
public class KnowledgeVectorRetirementClient {

    private final ObjectMapper json;
    private final URI uri;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public KnowledgeVectorRetirementClient(
        ObjectMapper json,
        @Value("${lexflow.ai-service-url}") String url,
        @Value("${lexflow.internal-secret}") String secret
    ) {
        this.json = json;
        this.secret = secret;
        URI base = InternalAiServiceUrl.parse(url);
        uri = base.resolve("/internal/v1/knowledge-vectors/retire");
    }

    public boolean delete(Artifact artifact) {
        var subscriber = new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("tenantId", Long.toString(artifact.tenantId()));
            body.put("knowledgeBaseId", Long.toString(artifact.baseId()));
            body.put("scope", artifact.scope());
            body.put("caseId", artifact.caseId() == null ? null : Long.toString(artifact.caseId()));
            body.put("documentId", Long.toString(artifact.documentId()));
            body.put("fileVersionId", Long.toString(artifact.fileVersionId()));
            body.put("indexVersion", artifact.indexVersion());
            body.put("collectionName", artifact.collectionName());
            body.put("dimension", artifact.dimension());
            body.put("modelIdentity", artifact.modelIdentity());
            var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("X-Internal-Secret", secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
            pending = http.sendAsync(request, ignored -> subscriber);
            var result = pending.get(8, TimeUnit.SECONDS);
            if (result.statusCode() != 200) {
                return false;
            }
            var node = json
                .reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readTree(result.body());
            return (
                node != null &&
                node.isObject() &&
                node.size() == 1 &&
                node.path("deleted").isBoolean() &&
                node.path("deleted").booleanValue()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            subscriber.abort();
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public synchronized void onSubscribe(Flow.Subscription incomingSubscription) {
            if (subscription != null || result.isDone()) {
                incomingSubscription.cancel();
                return;
            }
            subscription = incomingSubscription;
            incomingSubscription.request(1);
        }

        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (var buffer : buffers) {
                int length = buffer.remaining();
                if (length > 1024 - bytes.size()) {
                    abort();
                    return;
                }
                byte[] part = new byte[length];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        public synchronized void onComplete() {
            result.complete(bytes.toByteArray());
        }

        public synchronized void onError(Throwable failure) {
            abort();
        }

        private synchronized void abort() {
            if (
                result.completeExceptionally(new IOException("向量回收响应不可用")) &&
                subscription != null
            ) {
                subscription.cancel();
            }
        }
    }
}
