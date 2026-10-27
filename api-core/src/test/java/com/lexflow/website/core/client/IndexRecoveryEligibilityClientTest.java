package com.lexflow.website.core.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.service.KnowledgeIndexService.IndexChunk;
import com.lexflow.service.KnowledgeIndexService.IndexInput;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Local HTTP protocol checks only: no database, model, private source or remote request. */
class IndexRecoveryEligibilityClientTest {

    static final String SECRET = "eligibility-internal-test-only";
    static final String KEY = "eligibility-model-key-must-not-leave-Java";
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<Reply> reply = new AtomicReference<>();
    final AtomicReference<JsonNode> received = new AtomicReference<>();
    final AtomicReference<String> internalHeader = new AtomicReference<>();
    final AtomicInteger requests = new AtomicInteger(),
        redirected = new AtomicInteger();
    final CountDownLatch slowBodyStarted = new CountDownLatch(1);
    HttpServer server;
    ExecutorService executor;
    IndexRecoveryEligibilityClient client;

    record Reply(int status, String body, boolean slow) {}

    @BeforeEach
    void startLocalServer() throws Exception {
        reply.set(new Reply(200, "{\"status\":\"SAFE\"}", false));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/internal/v1/index-recovery/eligibility", this::respond);
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        client = new IndexRecoveryEligibilityClient(
            json,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            SECRET
        );
    }

    @AfterEach
    void stopLocalServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        Thread.interrupted();
    }

    IndexInput input() {
        return new IndexInput(
            "910000225",
            "8",
            "9",
            "10",
            "11",
            1,
            "CASE",
            "fixture",
            "https://models.example.test/v1",
            "embedding-test",
            KEY,
            "revision-only-test",
            List.of(new IndexChunk("1", "合成资料，仅用于资格协议测试"))
        );
    }

    void respond(HttpExchange exchange) throws java.io.IOException {
        try {
            requests.incrementAndGet();
            internalHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            var current = reply.get();
            if (current.status() == 302) exchange
                .getResponseHeaders()
                .set("Location", "/redirected");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(
                current.status(),
                current.slow() ? 0 : bytes.length == 0 ? -1 : bytes.length
            );
            if (current.slow()) {
                exchange.getResponseBody().write("{\"status\":".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                slowBodyStarted.countDown();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Test
    void allKnownStatusesUseTheAuthenticatedSanitizedContract() {
        for (String status : List.of(
            "SAFE",
            "BUSY",
            "UNCERTAIN",
            "FAILED",
            "INPUT_CHANGED",
            "UNAVAILABLE"
        )) {
            reply.set(new Reply(200, "{\"status\":\"" + status + "\"}", false));
            assertEquals(status, client.check(123, input()));
        }
        assertEquals(6, requests.get());
        assertEquals(SECRET, internalHeader.get());
        assertEquals("123", received.get().path("taskId").textValue());
        var value = received.get().path("input");
        assertEquals("910000225", value.path("tenantId").textValue());
        assertEquals("CASE", value.path("scope").textValue());
        assertEquals("revision-only-test", value.path("configurationRevision").textValue());
        assertFalse(value.has("apiKey"));
        assertFalse(received.get().toString().contains(KEY));
        assertEquals(
            "合成资料，仅用于资格协议测试",
            value.path("chunks").get(0).path("text").textValue()
        );
    }

    @Test
    void emptyMalformedDuplicateTrailingAndUnknownResultsFailClosed() {
        for (String body : List.of(
            "",
            "null",
            "[]",
            "{}",
            "{\"status\":null}",
            "{\"status\":1}",
            "{\"status\":\"UNKNOWN\"}",
            "{\"status\":\"SAFE\",\"unexpected\":true}",
            "{\"status\":\"UNCERTAIN\",\"status\":\"SAFE\"}",
            "{\"status\":\"SAFE\"}{\"status\":\"UNCERTAIN\"}",
            "{\"status\":\"SAFE\"} broken",
            "not-json"
        )) {
            reply.set(new Reply(200, body, false));
            assertEquals("UNAVAILABLE", client.check(123, input()));
        }
    }

    @Test
    void oversizedBodyIsCancelledBeforeBeingAcceptedAndNextRequestStillWorks() {
        reply.set(new Reply(200, "{\"status\":\"SAFE\"}" + " ".repeat(65536), false));
        assertEquals("UNAVAILABLE", client.check(123, input()));
        reply.set(new Reply(200, "{\"status\":\"SAFE\"}", false));
        assertEquals("SAFE", client.check(123, input()));
    }

    @Test
    void redirectAndNonSuccessCannotBeUsedAsEligibility() {
        for (int status : List.of(302, 400, 401, 403, 500)) {
            reply.set(new Reply(status, "{\"status\":\"SAFE\"}", false));
            assertEquals("UNAVAILABLE", client.check(123, input()));
        }
        assertEquals(0, redirected.get());
    }

    @Test
    void headersThenStalledBodyHasADeadlineAndCannotHoldRecoveryForever() {
        reply.set(new Reply(200, "", true));
        long started = System.nanoTime();
        assertEquals("UNAVAILABLE", client.check(123, input()));
        assertEquals(
            0,
            slowBodyStarted.getCount(),
            "The response actually delivered headers and a partial body"
        );
        assertTrue(
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 10000,
            "Body deadline must include reading after headers"
        );
    }

    @Test
    void networkFailureAndCallerInterruptionRemainUnavailable() {
        Thread.currentThread().interrupt();
        assertEquals("UNAVAILABLE", client.check(123, input()));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        server.stop(0);
        server = null;
        assertEquals("UNAVAILABLE", client.check(123, input()));
    }

    @Test
    void invalidDeploymentAddressesAreRejectedBeforeRequests() {
        for (String url : List.of(
            "http://example.test",
            "https://user:password@example.test",
            "https://example.test?secret=hidden",
            "https://example.test#fragment",
            "file:///tmp/test"
        ))
            assertThrows(IllegalArgumentException.class, () ->
                new IndexRecoveryEligibilityClient(json, url, SECRET)
            );
        assertEquals(0, requests.get());
    }
}
