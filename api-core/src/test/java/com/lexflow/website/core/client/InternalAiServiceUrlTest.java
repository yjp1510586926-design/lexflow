package com.lexflow.website.core.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternalAiServiceUrlTest {

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://ai-service:3001",
            "http://AI-SERVICE:3001",
            "http://127.0.0.1:3001",
            "http://localhost:3001",
            "http://[::1]:3001",
            "https://internal.example:3001",
        }
    )
    void deploymentAndExistingSecureAddressesAreAccepted(String value) {
        assertEquals(
            "/internal/probe",
            InternalAiServiceUrl.parse(value).resolve("/internal/probe").getPath()
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://public.example:3001",
            "http://ai-service.example:3001",
            "http://evil-ai-service:3001",
            "http://user:password@ai-service:3001",
            "http://ai-service:3001?token=private",
            "http://ai-service:3001#fragment",
            "ftp://ai-service:3001",
            "/relative",
        }
    )
    void publicHttpAndCredentialBearingAddressesAreRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> InternalAiServiceUrl.parse(value));
    }
}
