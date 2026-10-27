package com.lexflow.service.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditOfflineBufferTest {

    @TempDir
    Path parent;

    private RequestAuditEvent event() {
        return new RequestAuditEvent(
            UUID.randomUUID().toString(),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            null,
            null,
            "request:failure",
            503,
            "GET",
            "/api/v1/tasks/{taskId}",
            "7",
            UUID.randomUUID().toString()
        );
    }

    private AuditOfflineBuffer buffer(String name, AuditOperationalMetrics metrics) {
        return new AuditOfflineBuffer(parent.resolve(name).toString(), 10, 81920, metrics);
    }

    @Test
    void privateDurableRecordSurvivesAbruptIndependentProcessAndAcknowledge() throws Exception {
        Path path = parent.resolve("child");
        String eventId = UUID.randomUUID().toString();
        var child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            AuditBufferChild.class.getName(),
            path.toString(),
            eventId
        )
            .redirectErrorStream(true)
            .redirectOutput(parent.resolve("child.log").toFile())
            .start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(path)
        );
        for (String file : new String[] { ".key", ".lock", eventId + ".audit" })
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(path.resolve(file))
            );
        var restarted = buffer("child", new AuditOperationalMetrics());
        var pending = restarted.batch(10);
        assertEquals(1, pending.size());
        assertEquals(eventId, pending.getFirst().event().eventId());
        restarted.acknowledge(pending.getFirst());
        assertEquals(0, restarted.snapshot().records());
    }

    @Test
    void duplicateIdentityIsIdempotentButChangedPayloadIsRejected() {
        var journal = buffer("duplicate", new AuditOperationalMetrics());
        var original = event();
        journal.append(original);
        journal.append(original);
        assertEquals(1, journal.snapshot().records());
        var changed = new RequestAuditEvent(
            original.eventId(),
            original.occurredAt(),
            null,
            null,
            original.action(),
            500,
            original.method(),
            original.route(),
            original.objectId(),
            original.traceId()
        );
        assertEquals(
            AuditOfflineBuffer.Failure.INTEGRITY,
            assertThrows(AuditOfflineBuffer.BufferFailure.class, () ->
                journal.append(changed)
            ).kind()
        );
        assertEquals(original, journal.batch(10).getFirst().event());
    }

    @Test
    void exclusiveFileLockRejectsConcurrentWritersAndRetainsCrashTemporaryFiles() throws Exception {
        var journal = buffer("lock", new AuditOperationalMetrics());
        assertTrue(journal.snapshot().available());
        Path directory = parent.resolve("lock");
        try (
            var channel = java.nio.channels.FileChannel.open(
                directory.resolve(".lock"),
                StandardOpenOption.WRITE
            );
            var held = channel.lock()
        ) {
            assertEquals(
                AuditOfflineBuffer.Failure.BUSY,
                assertThrows(AuditOfflineBuffer.BufferFailure.class, () ->
                    journal.append(event())
                ).kind()
            );
        }
        Path partial = directory.resolve(UUID.randomUUID() + ".tmp");
        Files.createFile(partial);
        Files.setPosixFilePermissions(partial, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(partial, "partial crash record");
        var valid = event();
        journal.append(valid);
        assertEquals(2, journal.snapshot().records());
        assertEquals(valid, journal.batch(10).getFirst().event());
        assertTrue(Files.exists(partial));
    }

    @Test
    void hmacRejectsAlteredPayloadAndPreservesDamagedRecordWithoutStarvingValidEntries()
        throws Exception {
        var metrics = new AuditOperationalMetrics();
        var journal = buffer("altered", metrics);
        var first = event();
        var second = event();
        journal.append(first);
        journal.append(second);
        Path path = parent.resolve("altered").resolve(first.eventId() + ".audit");
        Files.writeString(path, Files.readString(path).replace("\"status\":503", "\"status\":500"));
        var valid = journal.batch(10);
        assertEquals(1, valid.size());
        assertEquals(second, valid.getFirst().event());
        assertTrue(Files.exists(path));
        assertTrue(metrics.count(AuditOperationalMetrics.Signal.INTEGRITY_FAILURE) > 0);
        journal.acknowledge(valid.getFirst());
        assertEquals(1, journal.snapshot().records());
    }

    @Test
    void malformedDuplicateKeysAndTrailingJsonAreRetainedAndNeverReplayed() throws Exception {
        var journal = buffer("malformed", new AuditOperationalMetrics());
        var event = event();
        journal.append(event);
        Path path = parent.resolve("malformed").resolve(event.eventId() + ".audit");
        String original = Files.readString(path);
        Files.writeString(path, original + "{}");
        assertTrue(journal.batch(10).isEmpty());
        assertTrue(Files.exists(path));
        Files.writeString(
            path,
            original.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
        );
        assertTrue(journal.batch(10).isEmpty());
        assertTrue(Files.exists(path));
    }

    @Test
    void recordAndByteLimitsRetainEveryExistingPendingRecord() throws Exception {
        var metrics = new AuditOperationalMetrics();
        var count = new AuditOfflineBuffer(parent.resolve("count").toString(), 1, 8192, metrics);
        var original = event();
        count.append(original);
        assertEquals(
            AuditOfflineBuffer.Failure.FULL,
            assertThrows(AuditOfflineBuffer.BufferFailure.class, () -> count.append(event())).kind()
        );
        assertEquals(original, count.batch(10).getFirst().event());
        assertEquals(1, count.snapshot().records());
        var bytes = new AuditOfflineBuffer(parent.resolve("bytes").toString(), 100, 8192, metrics);
        int appended = 0;
        while (true) {
            try {
                bytes.append(event());
                appended++;
            } catch (AuditOfflineBuffer.BufferFailure full) {
                assertEquals(AuditOfflineBuffer.Failure.FULL, full.kind());
                break;
            }
        }
        assertTrue(appended > 1);
        assertEquals(appended, bytes.snapshot().records());
        assertTrue(bytes.snapshot().bytes() <= 8192);
        assertTrue(metrics.count(AuditOperationalMetrics.Signal.BUFFER_FULL) >= 2);
    }

    @Test
    void symlinksHardlinksAndBroadFilePermissionsAreRejectedWithoutDeletingTargets()
        throws Exception {
        var journal = buffer("links", new AuditOperationalMetrics());
        var event = event();
        journal.append(event);
        Path path = parent.resolve("links").resolve(event.eventId() + ".audit");
        Path target = parent.resolve("target");
        Files.move(path, target);
        Files.createSymbolicLink(path, target);
        assertTrue(journal.batch(10).isEmpty());
        assertTrue(Files.exists(target));
        Files.delete(path);
        Files.createLink(path, target);
        assertTrue(journal.batch(10).isEmpty());
        assertTrue(Files.exists(target));
        Files.delete(path);
        Files.move(target, path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertTrue(journal.batch(10).isEmpty());
        assertTrue(Files.exists(path));
        Path shared = parent.resolve("shared");
        Files.createDirectory(shared);
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertFalse(buffer("shared", new AuditOperationalMetrics()).snapshot().available());
    }

    @Test
    void replacedOrMissingKeyAndChangedAcknowledgementKeepPendingRecords() throws Exception {
        var journal = buffer("key", new AuditOperationalMetrics());
        var event = event();
        journal.append(event);
        var entry = journal.batch(10).getFirst();
        Path path = entry.path();
        String original = Files.readString(path);
        Files.writeString(path, original + " ");
        assertThrows(AuditOfflineBuffer.BufferFailure.class, () -> journal.acknowledge(entry));
        assertTrue(Files.exists(path));
        Files.writeString(path, original);
        Path key = parent.resolve("key/.key");
        Files.write(key, new byte[32]);
        assertThrows(AuditOfflineBuffer.BufferFailure.class, () -> journal.batch(10));
        assertTrue(Files.exists(path));
        Files.delete(key);
        var restarted = buffer("key", new AuditOperationalMetrics());
        assertThrows(AuditOfflineBuffer.BufferFailure.class, () -> restarted.batch(10));
        assertFalse(Files.exists(key));
        assertTrue(Files.exists(path));
    }

    @Test
    void requestWriterBuffersOnlyDatabaseFailureAndReportsUnavailableIfBothStoresFail()
        throws Exception {
        var database = mock(RequestAuditDatabaseWriter.class);
        when(database.write(any())).thenThrow(new IllegalStateException("private db credential"));
        var metrics = new AuditOperationalMetrics();
        var journal = buffer("fallback", metrics);
        var writer = new RequestAuditWriter(database, journal, metrics);
        assertEquals(
            RequestAuditWriter.Outcome.BUFFERED,
            writer.record(
                null,
                "request:failure",
                503,
                "GET",
                "UNMATCHED",
                null,
                UUID.randomUUID().toString()
            )
        );
        assertEquals(1, journal.snapshot().records());
        assertEquals(1, metrics.count(AuditOperationalMetrics.Signal.BUFFERED));
        Path shared = parent.resolve("unsafe");
        Files.createDirectory(shared);
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        var unavailable = new RequestAuditWriter(database, buffer("unsafe", metrics), metrics);
        var failure = assertThrows(IllegalStateException.class, () ->
            unavailable.record(
                null,
                "request:failure",
                500,
                "GET",
                "UNMATCHED",
                null,
                UUID.randomUUID().toString()
            )
        );
        assertFalse(failure.toString().contains("credential"));
        assertEquals(1, metrics.count(AuditOperationalMetrics.Signal.UNAVAILABLE));
    }

    @Test
    void unverifiedAttributionRawUrlsAndBodyFieldsCannotEnterRequestEvent() {
        String eventId = UUID.randomUUID().toString(),
            trace = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThrows(IllegalArgumentException.class, () ->
            new RequestAuditEvent(
                eventId,
                now,
                1L,
                null,
                "request:failure",
                403,
                "GET",
                "UNMATCHED",
                null,
                trace
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new RequestAuditEvent(
                eventId,
                now,
                null,
                null,
                "request:failure",
                403,
                "GET",
                "/api/v1/items?password=secret",
                null,
                trace
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new RequestAuditEvent(
                eventId,
                now,
                1L,
                2L,
                "auth:login",
                401,
                "POST",
                "/api/v1/auth/login",
                null,
                trace
            )
        );
        var node = event().json();
        node.put("body", "private materials");
        assertThrows(IllegalArgumentException.class, () -> RequestAuditEvent.parse(node));
        assertThrows(IllegalArgumentException.class, () ->
            new RequestAuditEvent(
                eventId,
                now,
                null,
                null,
                "file:delete",
                200,
                "DELETE",
                "/api/v1/files/{id}",
                "1",
                trace
            )
        );
    }
}
