package com.lexflow.website.core.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkflowRecoveryEligibilityClientTest {

    @Test
    void workflowProtocolIncludesFixedInputAndVerifiedEvidenceButNeverRuntimeKey()
        throws Exception {
        var json = new ObjectMapper();
        var received = new AtomicReference<JsonNode>();
        var secret = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/workflow-recovery/eligibility", exchange -> {
            received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            secret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            byte[] body = "{\"status\":\"SAFE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new WorkflowRecoveryEligibilityClient(
                json,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "probe-secret"
            );
            for (String type : List.of("CONTRACT_REVIEW", "DOCUMENT_GENERATE", "CASE_ANALYSIS")) {
                var input = Map.of(
                    "tenantId",
                    "42",
                    "taskId",
                    "43",
                    "attempt",
                    1,
                    "stateVersion",
                    4,
                    "apiKey",
                    "must-never-leave-Java",
                    "instructions",
                    "合成材料".repeat(12000)
                );
                assertEquals(
                    "SAFE",
                    client.check(type, 43, input, List.of(Map.of("id", "7", "text", "合成依据")))
                );
                assertEquals("probe-secret", secret.get());
                assertEquals(type, received.get().path("type").asText());
                assertEquals(2, received.get().path("executionProtocol").asInt());
                assertEquals("43", received.get().path("taskId").asText());
                assertFalse(received.get().path("input").has("apiKey"));
                assertFalse(received.get().toString().contains("must-never-leave-Java"));
                assertEquals(
                    type.equals("CONTRACT_REVIEW"),
                    received.get().has("verifiedEvidence")
                );
            }
            assertEquals("UNAVAILABLE", client.check("DOCUMENT_PARSE", 43, Map.of(), null));
        } finally {
            server.stop(0);
        }
    }
}
