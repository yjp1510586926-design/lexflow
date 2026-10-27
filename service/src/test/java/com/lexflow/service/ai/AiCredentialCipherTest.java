package com.lexflow.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AiCredentialCipherTest {

    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripUsesUniqueNonceAndDoesNotExposePlaintext() {
        var cipher = new AiCredentialCipher(key);
        String first = cipher.encrypt(1, "LLM", "test-only-secret");
        String second = cipher.encrypt(1, "LLM", "test-only-secret");
        assertNotEquals(first, second);
        assertFalse(first.contains("test-only-secret"));
        assertEquals("test-only-secret", cipher.decrypt(1, "LLM", first));
    }

    @Test
    void copiedOrModifiedCiphertextCannotDecrypt() {
        var cipher = new AiCredentialCipher(key);
        String value = cipher.encrypt(1, "LLM", "test-only-secret");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(2, "LLM", value));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(1, "RERANK", value));
        byte[] bytes = Base64.getDecoder().decode(value.substring(3));
        bytes[15] ^= 1;
        assertThrows(IllegalStateException.class, () ->
            cipher.decrypt(1, "LLM", "v1:" + Base64.getEncoder().encodeToString(bytes))
        );
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        var other = new AiCredentialCipher(Base64.getEncoder().encodeToString(otherKey));
        assertThrows(IllegalStateException.class, () -> other.decrypt(1, "LLM", value));
    }

    @Test
    void absentMasterKeyDoesNotBreakConstructionButCannotStoreSecrets() {
        var cipher = new AiCredentialCipher("");
        assertFalse(cipher.configured());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt(1, "LLM", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new AiCredentialCipher("invalid"));
    }
}
