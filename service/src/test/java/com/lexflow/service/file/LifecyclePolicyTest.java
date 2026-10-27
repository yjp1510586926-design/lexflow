package com.lexflow.service.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lexflow.dao.CleanupTombstoneRepository;
import com.lexflow.service.CleanupLifecycleService;
import org.junit.jupiter.api.Test;

class LifecyclePolicyTest {

    @Test
    void disabledCompactionDoesNotScanOrDeleteAnything() {
        var repository = mock(CleanupTombstoneRepository.class);
        var storage = mock(PrivateObjectStorage.class);
        var service = new CleanupLifecycleService(new LifecyclePolicy(0, 0), repository, storage);
        assertFalse(service.compactFile());
        assertFalse(service.compactExport());
        verifyNoInteractions(repository, storage);
    }

    @Test
    void invalidDeploymentPolicyFailsRatherThanSilentlyEnablingDeletion() {
        assertThrows(IllegalArgumentException.class, () -> new LifecyclePolicy(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LifecyclePolicy(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new LifecyclePolicy(3651, 0));
        assertThrows(IllegalArgumentException.class, () -> new LifecyclePolicy(0, 3651));
        assertEquals(3650, new LifecyclePolicy(3650, 1).exportRetentionDays());
    }
}
