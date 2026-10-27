package com.lexflow.service.file;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class LocalObjectStorageTest {

    @TempDir
    Path temp;

    @Test
    void roundTripIsPrivateAndDeletionRemovesObject() throws Exception {
        String key = "integration-tests/" + UUID.randomUUID() + "/note";
        var store = new PrivateObjectStorage(
            "http://127.0.0.1:19000",
            "lexflow",
            "lexflow_minio_dev_only",
            "lexflow-files"
        );
        Path file = Files.writeString(temp.resolve("note"), "temporary integration content");
        try {
            store.put(key, file, "text/plain");
            try (var stream = store.open(key)) {
                assertArrayEquals(Files.readAllBytes(file), stream.readAllBytes());
            }
            var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:19000/lexflow-files/" + key))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            );
            assertEquals(403, response.statusCode());
            store.remove(key);
            var error = assertThrows(io.minio.errors.ErrorResponseException.class, () -> {
                try (var stream = store.open(key)) {
                    stream.read();
                }
            });
            assertEquals("NoSuchKey", error.errorResponse().code());
        } finally {
            store.remove(key);
        }
    }
}
