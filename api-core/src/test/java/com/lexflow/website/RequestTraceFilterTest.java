package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.audit.RequestAuditWriter;
import com.lexflow.website.core.filter.RequestAuditFilter;
import com.lexflow.website.core.filter.RequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTraceFilterTest {

    @Test
    void securityInfrastructureFailureReturnsGenericJsonAndRestoresTraceContext() throws Exception {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest("GET", "/api/v1/cases");
        request.addHeader("X-Trace-Id", "fake-client-trace");
        try (var outer = TraceContext.startRequest()) {
            var audit = org.mockito.Mockito.mock(RequestAuditWriter.class);
            new RequestTraceFilter(audit).doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("synthetic-key-never-return-this");
            });
            var body = new ObjectMapper().readTree(response.getContentAsString());
            assertEquals(500, response.getStatus());
            assertEquals(500, body.path("code").asInt());
            assertEquals(response.getHeader("X-Trace-Id"), body.path("traceId").asText());
            assertFalse(response.getContentAsString().contains("synthetic-key"));
            assertNotEquals("fake-client-trace", body.path("traceId").asText());
            assertEquals(outer.traceId(), TraceContext.currentOrNew());
            org.mockito.Mockito.verify(audit).record(
                null,
                "request:failure",
                500,
                "GET",
                "UNMATCHED",
                null,
                body.path("traceId").asText()
            );
        }
        assertNull(org.slf4j.MDC.get("traceId"));
    }

    @Test
    void auditFailureDoesNotReplaceTheResponseAndPreviouslyAttemptedAuditIsNotDuplicated()
        throws Exception {
        var audit = org.mockito.Mockito.mock(RequestAuditWriter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-store-failure"))
            .when(audit)
            .record(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("request:failure"),
                org.mockito.ArgumentMatchers.eq(500),
                org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("UNMATCHED"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString()
            );
        var filter = new RequestTraceFilter(audit);
        var first = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/cases"), first, (req, res) -> {
            throw new IllegalStateException("failure");
        });
        assertEquals(500, first.getStatus());
        assertFalse(first.getContentAsString().contains("synthetic-store-failure"));
        var secondRequest = new MockHttpServletRequest("GET", "/api/v1/cases");
        secondRequest.setAttribute(RequestAuditFilter.ATTEMPTED, Boolean.TRUE);
        var second = new MockHttpServletResponse();
        filter.doFilter(secondRequest, second, (req, res) -> {
            throw new IllegalStateException("already audited");
        });
        assertEquals(500, second.getStatus());
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.times(1)).record(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void filterErrorWriterEscapesQuotesNewlinesAndControlCharactersAsValidJson() throws Exception {
        var response = new MockHttpServletResponse();
        String message = "引用\"与换行\n以及制表\t和反斜杠\\";
        try (var scope = TraceContext.startRequest()) {
            ApiResponse.write(response, ApiResponse.badRequest(message));
            var parsed = new ObjectMapper().readTree(response.getContentAsString());
            assertEquals(message, parsed.path("message").asText());
            assertEquals(scope.traceId(), parsed.path("traceId").asText());
            assertEquals(scope.traceId(), response.getHeader("X-Trace-Id"));
        }
    }
}
