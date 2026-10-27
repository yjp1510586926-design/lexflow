package com.lexflow.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lexflow.dao.AiConfigurationMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.ai.AiCredentialCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiConfigurationServiceTest {

    @Test
    void persistenceReceivesOnlyTenantBoundCiphertext() {
        var mapper = mock(AiConfigurationMapper.class);
        var cipher = new AiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var service = new AiConfigurationService(mapper, cipher);
        service.save(7, "LLM", "custom", "https://example.com/v1", "model", "test-secret");
        var captured = ArgumentCaptor.forClass(String.class);
        verify(mapper).save(
            eq(7L),
            eq("LLM"),
            eq("custom"),
            eq("https://example.com/v1"),
            eq("model"),
            captured.capture()
        );
        assertNotEquals("test-secret", captured.getValue());
        assertEquals("test-secret", cipher.decrypt(7, "LLM", captured.getValue()));
        assertThrows(IllegalStateException.class, () ->
            cipher.decrypt(8, "LLM", captured.getValue())
        );
    }

    @Test
    void missingMasterKeyAndUnsafeAddressNeverPersist() {
        var mapper = mock(AiConfigurationMapper.class);
        var unconfigured = new AiConfigurationService(mapper, new AiCredentialCipher(""));
        assertThrows(BusinessException.class, () ->
            unconfigured.save(1, "LLM", "custom", "https://example.com", "model", "secret")
        );
        var service = new AiConfigurationService(
            mapper,
            new AiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]))
        );
        for (var url : new String[] {
            "http://example.com",
            "https://user:password@example.com",
            "https://example.com?key=secret",
        })
            assertThrows(BusinessException.class, () ->
                service.save(1, "LLM", "custom", url, "model", "secret")
            );
        verifyNoInteractions(mapper);
    }
}
