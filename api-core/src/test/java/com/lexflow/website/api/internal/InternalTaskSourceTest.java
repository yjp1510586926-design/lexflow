package com.lexflow.website.api.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.TaskResultService;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.website.biz.TaskContextBiz;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InternalTaskSourceTest {

    @Test
    void contextReturnsAuthorizedResourceVersionForDeliveryBinding() {
        var contexts = mock(TaskContextBiz.class);
        var storage = mock(PrivateObjectStorage.class);
        var row = new java.util.HashMap<String, Object>();
        row.put("scope", "CASE");
        row.put("case_id", 7L);
        row.put("resource_version_id", 92L);
        row.put("status", "RUNNING");
        row.put("id", 1L);
        row.put("tenant_id", 8L);
        row.put("type", "CONTRACT_REVIEW");
        row.put("attempt", 0);
        row.put("state_version", 5);
        row.put("idempotency_key", "contract:1");
        when(contexts.get(1)).thenReturn(row);
        var controller = new InternalTaskController(
            contexts,
            mock(TaskResultService.class),
            storage
        );
        assertEquals("92", controller.getContext(1).get("resourceVersionId"));
        row.remove("resource_version_id");
        assertNull(controller.getContext(1).get("resourceVersionId"));
        verifyNoInteractions(storage);
    }

    @Test
    void sourceUsesOnlyAuthorizedPathAndRejectsStaleAttempt() throws Exception {
        var contexts = mock(TaskContextBiz.class);
        var storage = mock(PrivateObjectStorage.class);
        var controller = new InternalTaskController(
            contexts,
            mock(TaskResultService.class),
            storage
        );
        when(contexts.get(1)).thenReturn(
            Map.of(
                "attempt",
                2,
                "state_version",
                3,
                "status",
                "QUEUED",
                "size_bytes",
                4L,
                "object_key",
                "authorized/path"
            )
        );
        assertThrows(BusinessException.class, () -> controller.source(1, 1, 3));
        verifyNoInteractions(storage);
        when(storage.open("authorized/path")).thenReturn(
            new ByteArrayInputStream(new byte[] { 1, 2, 3, 4 })
        );
        var response = controller.source(1, 2, 3);
        try (var stream = response.getBody().getInputStream()) {
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, stream.readAllBytes());
        }
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(4, response.getHeaders().getContentLength());
    }

    @Test
    void revokedAccessNeverOpensStorage() {
        var contexts = mock(TaskContextBiz.class);
        var storage = mock(PrivateObjectStorage.class);
        when(contexts.get(1)).thenThrow(BusinessException.forbidden("revoked"));
        var controller = new InternalTaskController(
            contexts,
            mock(TaskResultService.class),
            storage
        );
        assertThrows(BusinessException.class, () -> controller.source(1, 0, 1));
        verifyNoInteractions(storage);
    }

    @Test
    void revocationAfterOpeningObjectClosesStreamAndReturnsNoBody() throws Exception {
        var contexts = mock(TaskContextBiz.class);
        var storage = mock(PrivateObjectStorage.class);
        var stream = spy(new ByteArrayInputStream(new byte[] { 1, 2, 3, 4 }));
        when(contexts.get(1))
            .thenReturn(
                Map.of(
                    "attempt",
                    0,
                    "state_version",
                    1,
                    "status",
                    "QUEUED",
                    "size_bytes",
                    4L,
                    "object_key",
                    "authorized/path"
                )
            )
            .thenThrow(BusinessException.forbidden("revoked"));
        when(storage.open("authorized/path")).thenReturn(stream);
        var controller = new InternalTaskController(
            contexts,
            mock(TaskResultService.class),
            storage
        );
        assertThrows(BusinessException.class, () -> controller.source(1, 0, 1));
        verify(stream).close();
    }
}
