package com.lexflow.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lexflow.dao.FileCleanupMapper;
import com.lexflow.service.file.PrivateObjectStorage;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileCleanupServiceTest {

    @Test
    void storageFailurePersistsRetryWithoutMarkingComplete() throws Exception {
        var jobs = mock(FileCleanupMapper.class);
        var storage = mock(PrivateObjectStorage.class);
        when(jobs.lockNext()).thenReturn(
            Map.of("id", 1L, "object_key", "private/object", "attempts", 2)
        );
        doThrow(new IOException("sensitive internal endpoint"))
            .when(storage)
            .remove("private/object");
        assertTrue(new FileCleanupService(jobs, storage).processOne());
        verify(jobs).retry(1, 40, "IOException");
        verify(jobs, never()).done(anyLong());
    }

    @Test
    void retryCanSucceedAndComplete() throws Exception {
        var jobs = mock(FileCleanupMapper.class);
        var storage = mock(PrivateObjectStorage.class);
        when(jobs.lockNext()).thenReturn(
            Map.of("id", 2L, "object_key", "private/object", "attempts", 3)
        );
        assertTrue(new FileCleanupService(jobs, storage).processOne());
        verify(storage).remove("private/object");
        verify(jobs).done(2);
        verify(jobs, never()).retry(anyLong(), anyInt(), anyString());
    }

    @Test
    void emptyQueueDoesNotCallStorage() {
        var jobs = mock(FileCleanupMapper.class);
        var storage = mock(PrivateObjectStorage.class);
        assertFalse(new FileCleanupService(jobs, storage).processOne());
        verifyNoInteractions(storage);
    }
}
