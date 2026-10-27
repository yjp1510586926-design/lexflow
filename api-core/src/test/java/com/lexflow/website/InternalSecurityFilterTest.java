package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import com.lexflow.website.core.filter.InternalSecurityFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class InternalSecurityFilterTest {

    private InternalSecurityFilter filter(String secret) {
        var filter = new InternalSecurityFilter();
        ReflectionTestUtils.setField(filter, "internalSecret", secret);
        return filter;
    }

    @Test
    void absentWrongOversizedOrEmptyConfiguredSecretsRejectTheMetricsPath() throws Exception {
        for (String configured : new String[] { "synthetic-internal-secret", "" }) {
            for (String supplied : new String[] { "", "wrong", "x".repeat(1025) }) {
                var request = new MockHttpServletRequest("GET", "/internal/v1/metrics");
                request.addHeader("X-Internal-Secret", supplied);
                var response = new MockHttpServletResponse();
                var invoked = new AtomicBoolean();
                filter(configured).doFilter(request, response, (req, res) -> invoked.set(true));
                assertEquals(401, response.getStatus());
                assertFalse(invoked.get());
                assertFalse(
                    response
                        .getContentAsString()
                        .contains(supplied.equals("") ? "lexflow_" : supplied)
                );
            }
        }
        var absent = new MockHttpServletRequest("GET", "/internal/v1/metrics");
        var response = new MockHttpServletResponse();
        filter("synthetic-internal-secret").doFilter(absent, response, (req, res) ->
            fail("Must reject missing secret")
        );
        assertEquals(401, response.getStatus());
    }

    @Test
    void exactValidSecretPassesAndBusinessRoutesRemainUnderTheirOwnAuthentication()
        throws Exception {
        var request = new MockHttpServletRequest("GET", "/internal/v1/metrics");
        request.addHeader("X-Internal-Secret", "synthetic-internal-secret");
        var invoked = new AtomicBoolean();
        filter("synthetic-internal-secret").doFilter(
            request,
            new MockHttpServletResponse(),
            (req, res) -> invoked.set(true)
        );
        assertTrue(invoked.get());
        invoked.set(false);
        filter("synthetic-internal-secret").doFilter(
            new MockHttpServletRequest("GET", "/api/v1/cases"),
            new MockHttpServletResponse(),
            (req, res) -> invoked.set(true)
        );
        assertTrue(invoked.get());
    }
}
