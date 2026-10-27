package com.lexflow.service.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Synthetic child process deliberately halts after durable append, without a shutdown hook. */
public final class AuditBufferChild {

    public static void main(String[] arguments) {
        var buffer = new AuditOfflineBuffer(arguments[0], 10, 81920, new AuditOperationalMetrics());
        buffer.append(
            new RequestAuditEvent(
                arguments[1],
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                null,
                null,
                "request:failure",
                503,
                "GET",
                "UNMATCHED",
                null,
                UUID.randomUUID().toString()
            )
        );
        Runtime.getRuntime().halt(0);
    }
}
