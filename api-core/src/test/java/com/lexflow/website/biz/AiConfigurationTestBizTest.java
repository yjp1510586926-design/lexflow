package com.lexflow.website.biz;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.AiConfigurationTestService;
import com.lexflow.service.AiConfigurationTestService.Claim;
import com.lexflow.service.AiConfigurationTestService.Outcome;
import com.lexflow.service.AiConfigurationTestService.View;
import com.lexflow.service.identity.Identity;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A loopback synthetic Node endpoint verifies paid-request boundaries; no model provider is contacted. */
class AiConfigurationTestBizTest {

    private static final String REVISION = "8ff15802-3cd0-414c-a1b4-48e3e10f56fa";
    private static final String FAKE_KEY = "synthetic-only-secret-do-not-persist";
    private static final String INTERNAL_SECRET = "synthetic-internal-secret";
    private static final Identity ACTOR = new Identity(11, 7, 0, "Synthetic admin");
    private final ObjectMapper json = new ObjectMapper();
    private final AiConfigurationTestService tests = mock(AiConfigurationTestService.class);
    private final AtomicInteger calls = new AtomicInteger(),
        redirectCalls = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> receivedSecret = new AtomicReference<>();
    private final AtomicReference<Outcome> finished = new AtomicReference<>();
    private final AtomicReference<Reply> reply = new AtomicReference<>(
        new Reply(200, "{\"status\":\"SUCCEEDED\",\"elapsedMs\":4}", false, false)
    );
    private HttpServer server;
    private String base;

    private record Reply(int status, String body, boolean abort, boolean redirect) {}

    @BeforeEach
    void syntheticEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/ai/configuration-test", exchange -> {
            calls.incrementAndGet();
            request.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            receivedSecret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            var response = reply.get();
            if (response.abort()) {
                exchange.close();
                return;
            }
            if (response.redirect()) exchange
                .getResponseHeaders()
                .set("Location", base + "/redirect-target");
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.createContext("/redirect-target", exchange -> {
            redirectCalls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        doAnswer(invocation -> {
            assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "HTTP orchestration must not keep a SQL transaction open"
            );
            finished.set(invocation.getArgument(1));
            return null;
        })
            .when(tests)
            .finish(any(Claim.class), any(Outcome.class));
    }

    @AfterEach
    void stopSyntheticEndpoint() {
        if (server != null) server.stop(0);
    }

    private AiConfigurationTestBiz configuredBiz(String purpose) {
        var prepared = new View(
            "42",
            purpose,
            REVISION,
            "PREPARED",
            "synthetic",
            null,
            null,
            Instant.now()
        );
        var claim = new Claim(
            42,
            ACTOR,
            purpose,
            REVISION,
            new AiConfigurationService.InternalModel(
                "synthetic",
                "https://model.example/v1",
                "synthetic-model",
                FAKE_KEY
            )
        );
        when(tests.prepare(eq(ACTOR), eq(purpose), eq(REVISION), anyString())).thenReturn(prepared);
        when(tests.begin(ACTOR, 42)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return claim;
        });
        when(tests.get(ACTOR, 42)).thenAnswer(invocation -> {
            var result = finished.get();
            return new View(
                "42",
                purpose,
                REVISION,
                result == null ? "PROCESSING" : result.status(),
                "安全结果",
                result == null ? null : result.elapsedMs(),
                result == null ? null : result.dimension(),
                prepared.createdAt()
            );
        });
        return new AiConfigurationTestBiz(tests, json, base, INTERNAL_SECRET);
    }

    @Test
    void immutableClaimIsSentOnceAndRepeatedPostReadsTheExistingResult() throws Exception {
        var biz = configuredBiz("LLM");
        String id = UUID.randomUUID().toString();
        assertFalse(
            AnnotatedElementUtils.hasAnnotation(AiConfigurationTestBiz.class, Transactional.class)
        );
        assertFalse(
            AnnotatedElementUtils.hasAnnotation(
                AiConfigurationTestBiz.class.getMethod(
                    "test",
                    Identity.class,
                    String.class,
                    String.class,
                    String.class
                ),
                Transactional.class
            )
        );
        assertEquals("SUCCEEDED", biz.test(ACTOR, "LLM", REVISION, id).status());
        assertEquals(1, calls.get());
        assertEquals(INTERNAL_SECRET, receivedSecret.get());
        var body = request.get();
        assertEquals(8, body.size());
        assertEquals("7", body.path("tenantId").asText());
        assertEquals("42", body.path("testId").asText());
        assertEquals(REVISION, body.path("revision").asText());
        assertEquals("LLM", body.path("purpose").asText());
        assertEquals(FAKE_KEY, body.path("apiKey").asText());
        assertFalse(finished.get().toString().contains(FAKE_KEY));
        when(tests.begin(ACTOR, 42)).thenReturn(null);
        assertEquals("SUCCEEDED", biz.test(ACTOR, "LLM", REVISION, id).status());
        assertEquals(1, calls.get());
        verify(tests, times(1)).finish(any(Claim.class), any(Outcome.class));
        var order = inOrder(tests);
        order.verify(tests).prepare(ACTOR, "LLM", REVISION, id);
        order.verify(tests).begin(ACTOR, 42);
        order.verify(tests).finish(any(Claim.class), any(Outcome.class));
        order.verify(tests).get(ACTOR, 42);
        order.verify(tests).prepare(ACTOR, "LLM", REVISION, id);
        order.verify(tests).begin(ACTOR, 42);
        order.verify(tests).get(ACTOR, 42);
    }

    @Test
    void unclaimedProcessingAndExpiredIntentsNeverContactTheEndpoint() {
        var biz = configuredBiz("LLM");
        when(tests.begin(ACTOR, 42)).thenReturn(null);
        assertEquals(
            "PROCESSING",
            biz.test(ACTOR, "LLM", REVISION, UUID.randomUUID().toString()).status()
        );
        finished.set(new Outcome("UNKNOWN", 0, null, "RESPONSE_UNKNOWN"));
        assertEquals(
            "UNKNOWN",
            biz.test(ACTOR, "LLM", REVISION, UUID.randomUUID().toString()).status()
        );
        assertEquals(0, calls.get());
        verify(tests, never()).finish(any(), any());
    }

    @Test
    void malformedOrOversizedResultsStayUnknownAndCannotPersistProviderText() {
        var biz = configuredBiz("EMBEDDING");
        String[] responses = {
            "{\"status\":\"SUCCEEDED\",\"elapsedMs\":1,\"dimension\":1}",
            "{\"status\":\"SUCCEEDED\",\"elapsedMs\":1,\"dimension\":4097}",
            "{\"status\":\"SUCCEEDED\",\"elapsedMs\":-1,\"dimension\":8}",
            "{\"status\":\"SUCCEEDED\",\"elapsedMs\":1.5,\"dimension\":8}",
            "{\"status\":\"SUCCEEDED\",\"elapsedMs\":2147483648,\"dimension\":8}",
            "{\"status\":\"untrusted-provider-status\",\"elapsedMs\":1}",
            "null",
            "not-json " + FAKE_KEY,
            "x".repeat(4097) + FAKE_KEY,
        };
        for (String body : responses) {
            reply.set(new Reply(200, body, false, false));
            assertEquals(
                "UNKNOWN",
                biz.test(ACTOR, "EMBEDDING", REVISION, UUID.randomUUID().toString()).status()
            );
            assertEquals("RESPONSE_UNKNOWN", finished.get().errorCode());
            assertNull(finished.get().dimension());
            assertFalse(finished.get().toString().contains(FAKE_KEY));
            assertFalse(finished.get().toString().contains("untrusted-provider-status"));
        }
        assertEquals(responses.length, calls.get());
        reply.set(
            new Reply(
                200,
                "{\"status\":\"SUCCEEDED\",\"elapsedMs\":2,\"dimension\":32,\"apiKey\":\"" +
                    FAKE_KEY +
                    "\"}",
                false,
                false
            )
        );
        assertEquals(
            "SUCCEEDED",
            biz.test(ACTOR, "EMBEDDING", REVISION, UUID.randomUUID().toString()).status()
        );
        assertEquals(32, finished.get().dimension());
        assertNull(finished.get().errorCode());
        assertFalse(finished.get().toString().contains(FAKE_KEY));
    }

    @Test
    void redirectRejectedResponseAndLostResponseAreNeverAutomaticallyRetried() {
        var biz = configuredBiz("LLM");
        reply.set(new Reply(307, "redirect", false, true));
        assertEquals(
            "UNKNOWN",
            biz.test(ACTOR, "LLM", REVISION, UUID.randomUUID().toString()).status()
        );
        assertEquals(1, calls.get());
        assertEquals(0, redirectCalls.get());
        reply.set(new Reply(503, "provider rejected: " + FAKE_KEY, false, false));
        assertEquals(
            "UNKNOWN",
            biz.test(ACTOR, "LLM", REVISION, UUID.randomUUID().toString()).status()
        );
        assertEquals(2, calls.get());
        assertFalse(finished.get().toString().contains(FAKE_KEY));
        reply.set(new Reply(200, "", true, false));
        assertEquals(
            "UNKNOWN",
            biz.test(ACTOR, "LLM", REVISION, UUID.randomUUID().toString()).status()
        );
        assertEquals(3, calls.get());
        assertEquals("RESPONSE_UNKNOWN", finished.get().errorCode());
    }

    @Test
    void authorizationFailureAndReadOnlyEndpointsNeverIssueModelRequests() {
        var biz = configuredBiz("LLM");
        when(tests.prepare(eq(ACTOR), eq("LLM"), eq(REVISION), anyString())).thenThrow(
            BusinessException.forbidden("仅管理员可测试")
        );
        assertEquals(
            403,
            assertThrows(BusinessException.class, () ->
                biz.test(ACTOR, "LLM", REVISION, UUID.randomUUID().toString())
            ).getCode()
        );
        assertNull(biz.latest(ACTOR, "LLM", REVISION));
        assertEquals("PROCESSING", biz.get(ACTOR, 42).status());
        assertEquals(0, calls.get());
        verify(tests, never()).begin(any(), anyLong());
        verify(tests, never()).finish(any(), any());
    }
}
