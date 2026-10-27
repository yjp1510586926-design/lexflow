package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lexflow.service.AuditReplayService;
import com.lexflow.service.IdentityService;
import com.lexflow.service.OrganizationService;
import com.lexflow.service.audit.AuditOfflineBuffer;
import com.lexflow.service.audit.AuditOperationalMetrics;
import com.lexflow.service.audit.RequestAuditDatabaseWriter;
import com.lexflow.service.audit.RequestAuditEvent;
import com.lexflow.service.audit.RequestAuditWriter;
import com.lexflow.service.identity.Identity;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Never runs against the default/shared database. The runner owns and later drops this entire empty schema. */
@SpringBootTest(
    properties = {
        "scheduling.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
    }
)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(
    named = "LEXFLOW_AUDIT_ISOLATED_DB",
    matches = "lexflow_audit_[a-f0-9]{24}"
)
class AuditOperationsIntegrationTest {

    private static final String SECRET = UUID.randomUUID().toString();
    private static long tenant;

    @TempDir
    Path temporary;

    @Autowired
    JdbcTemplate db;

    @Autowired
    MockMvc mvc;

    @Autowired
    OrganizationService organizations;

    @Autowired
    IdentityService identities;

    @Autowired
    RequestAuditWriter writer;

    @Autowired
    RequestAuditDatabaseWriter database;

    @Autowired
    AuditOfflineBuffer buffer;

    @Autowired
    AuditReplayService replay;

    @Autowired
    AuditOperationalMetrics metrics;

    @Autowired
    PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void ownedProperties(DynamicPropertyRegistry properties) {
        String schema = System.getenv("LEXFLOW_AUDIT_ISOLATED_DB");
        if (
            schema == null || !schema.matches("lexflow_audit_[a-f0-9]{24}")
        ) throw new IllegalArgumentException("Owned local audit schema required");
        String url =
            "jdbc:mysql://127.0.0.1:13306/" +
            schema +
            "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.datasource.username", () -> required("LEXFLOW_AUDIT_ISOLATED_USER"));
        properties.add("spring.flyway.user", () -> required("LEXFLOW_AUDIT_ISOLATED_USER"));
        properties.add("spring.datasource.password", () ->
            required("LEXFLOW_AUDIT_ISOLATED_PASSWORD")
        );
        properties.add("spring.flyway.password", () -> required("LEXFLOW_AUDIT_ISOLATED_PASSWORD"));
        properties.add("lexflow.audit.buffer-directory", () ->
            required("LEXFLOW_AUDIT_ISOLATED_BUFFER")
        );
        properties.add("spring.session.redis.namespace", () -> "lexflow:session:audit:" + schema);
        properties.add("lexflow.internal-secret", () -> SECRET);
        properties.add("LEXFLOW_AI_MASTER_KEY", () ->
            java.util.Base64.getEncoder().encodeToString(new byte[32])
        );
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
            "Missing isolated audit setting"
        );
        return value;
    }

    private RequestAuditEvent event() {
        return new RequestAuditEvent(
            UUID.randomUUID().toString(),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            null,
            null,
            "request:failure",
            503,
            "GET",
            "UNMATCHED",
            null,
            UUID.randomUUID().toString()
        );
    }

    private void offline(Runnable action) {
        assertEquals(
            System.getenv("LEXFLOW_AUDIT_ISOLATED_DB"),
            db.queryForObject("SELECT DATABASE()", String.class)
        );
        db.execute("RENAME TABLE audit_log TO audit_log_owned_offline");
        try {
            action.run();
        } finally {
            db.execute("RENAME TABLE audit_log_owned_offline TO audit_log");
        }
    }

    @Test
    @Order(1)
    void emptyInstallGrantsDefaultLawyerGenerationAndPreservesReaderAndCustomRolePermissions() {
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class));
        assertFalse(
            Boolean.TRUE.equals(
                db.queryForObject(
                    "SELECT initialized FROM installation_guard WHERE id=1",
                    Boolean.class
                )
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='25' AND success=1",
                Integer.class
            )
        );
        tenant = Long.parseLong(
            organizations.initialize(
                "Synthetic isolated audit tenant",
                "audit-owned-admin",
                "Audit-owned-password-2026!"
            )
        );
        Identity admin = identities
            .authenticate("audit-owned-admin", "Audit-owned-password-2026!", tenant)
            .orElseThrow();
        long lawyer = Long.parseLong(
            organizations.createUser(
                admin,
                "audit-owned-lawyer",
                "Audit-owned-password-2026!",
                "Synthetic",
                "LAWYER"
            )
        );
        long reader = Long.parseLong(
            organizations.createUser(
                admin,
                "audit-owned-reader",
                "Audit-owned-password-2026!",
                "Synthetic",
                "READER"
            )
        );
        assertTrue(
            identities.hasPermission(new Identity(lawyer, tenant, 0, "Synthetic"), "ai:generate")
        );
        assertTrue(
            identities.hasPermission(new Identity(lawyer, tenant, 0, "Synthetic"), "ai:review")
        );
        assertFalse(
            identities.hasPermission(new Identity(reader, tenant, 0, "Synthetic"), "ai:generate")
        );
        db.update(
            "INSERT INTO sys_role(tenant_id,code,name) VALUES(?,'CUSTOM','Owned role')",
            tenant
        );
        long custom = db.queryForObject(
            "SELECT id FROM sys_role WHERE tenant_id=? AND code='CUSTOM'",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'case:read')",
            tenant,
            custom
        );
        assertThrows(IllegalStateException.class, () ->
            organizations.initialize("Again", "other-admin", "Other-password-2026!")
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_id=? AND permission_code='ai:generate'",
                Integer.class,
                custom
            )
        );
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class));
    }

    @Test
    @Order(2)
    void databaseOutageKeepsHttpResponseBuffersSafelyAndReplaysOriginalEventsOnce()
        throws Exception {
        assertEquals(0, buffer.snapshot().records());
        String[] requestTrace = new String[1];
        offline(() -> {
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
            try {
                var result = mvc
                    .perform(
                        get("/internal/v1/metrics")
                            .header("X-Internal-Secret", "wrong-private-secret")
                            .queryParam("apiKey", "private-model-key")
                    )
                    .andReturn();
                assertEquals(401, result.getResponse().getStatus());
                requestTrace[0] = result.getResponse().getHeader("X-Trace-Id");
                assertNotNull(requestTrace[0]);
                assertFalse(result.getResponse().getContentAsString().contains("private"));
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
            assertEquals(2, buffer.snapshot().records());
            assertEquals(0, replay.replay());
            assertEquals(2, buffer.snapshot().records());
        });
        var entries = buffer.batch(100);
        assertEquals(2, entries.size());
        for (var entry : entries) {
            String stored = java.nio.file.Files.readString(entry.path());
            assertFalse(stored.contains("wrong-private-secret"));
            assertFalse(stored.contains("private-model-key"));
        }
        assertEquals(2, replay.replay());
        assertEquals(0, buffer.snapshot().records());
        assertEquals(0, replay.replay());
        for (var entry : entries) {
            assertEquals(
                entry.event().payloadHash(),
                db.queryForObject(
                    "SELECT payload_sha256 FROM audit_log WHERE event_uuid=?",
                    String.class,
                    entry.event().eventId()
                )
            );
            assertEquals(
                entry.event().occurredAt(),
                db.queryForObject(
                    "SELECT created_at FROM audit_log WHERE event_uuid=?",
                    (row, index) -> row.getTimestamp(1).toInstant(),
                    entry.event().eventId()
                )
            );
            assertEquals(
                1,
                db.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE event_uuid=?",
                    Integer.class,
                    entry.event().eventId()
                )
            );
        }
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE trace_id=? AND actor_id IS NULL AND tenant_id IS NULL",
                Integer.class,
                requestTrace[0]
            )
        );
    }

    @Test
    @Order(3)
    void commitThenAcknowledgementFailureReplaysIdempotentlyAndConflictRetainsTheRecord() {
        var localMetrics = new AuditOperationalMetrics();
        var journal = new AuditOfflineBuffer(
            temporary.resolve("lost-ack").toString(),
            10,
            81920,
            localMetrics
        ) {
            private boolean fail = true;

            @Override
            public synchronized void acknowledge(Entry entry) {
                if (fail) {
                    fail = false;
                    throw new IllegalStateException("Synthetic acknowledgement failure");
                }
                super.acknowledge(entry);
            }
        };
        var original = event();
        journal.append(original);
        var localReplay = new AuditReplayService(database, journal, localMetrics, 10);
        assertEquals(0, localReplay.replay());
        assertEquals(1, journal.snapshot().records());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_uuid=?",
                Integer.class,
                original.eventId()
            )
        );
        assertEquals(1, localReplay.replay());
        assertEquals(0, journal.snapshot().records());
        assertEquals(1, localMetrics.count(AuditOperationalMetrics.Signal.DUPLICATE));
        var changed = new RequestAuditEvent(
            original.eventId(),
            original.occurredAt(),
            null,
            null,
            original.action(),
            500,
            original.method(),
            original.route(),
            null,
            original.traceId()
        );
        journal.append(changed);
        assertEquals(0, localReplay.replay());
        assertEquals(1, journal.snapshot().records());
        assertEquals(
            original.payloadHash(),
            db.queryForObject(
                "SELECT payload_sha256 FROM audit_log WHERE event_uuid=?",
                String.class,
                original.eventId()
            )
        );
    }

    @Test
    @Order(4)
    void independentRequestCommitSurvivesOuterRollbackWhileBusinessSuccessCannotFallBack() {
        String trace = UUID.randomUUID().toString();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertEquals(
                RequestAuditWriter.Outcome.COMMITTED,
                writer.record(null, "request:failure", 409, "POST", "/api/v1/cases", null, trace)
            );
            tx.setRollbackOnly();
        });
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE trace_id=?",
                Integer.class,
                trace
            )
        );
        Identity admin = identities
            .authenticate("audit-owned-admin", "Audit-owned-password-2026!", tenant)
            .orElseThrow();
        long buffered = buffer.snapshot().records();
        offline(() ->
            assertThrows(RuntimeException.class, () ->
                organizations.createUser(
                    admin,
                    "must-rollback-audit-owned",
                    "Audit-owned-password-2026!",
                    "Synthetic",
                    "LAWYER"
                )
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username='must-rollback-audit-owned'",
                Integer.class
            )
        );
        assertEquals(buffered, buffer.snapshot().records());
    }

    @Test
    @Order(5)
    void protectedMetricsExposeFixedAggregatesAndNeverCredentialsIdentifiersOrInventedZeroes()
        throws Exception {
        long admin = db.queryForObject(
            "SELECT user_id FROM tenant_user WHERE tenant_id=? ORDER BY user_id LIMIT 1",
            Long.class,
            tenant
        );
        String nonce = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO async_task(tenant_id,type,status,idempotency_key,created_by,created_at) VALUES(?,'DOCUMENT_PARSE','QUEUED',?,?,UTC_TIMESTAMP()-INTERVAL 10 MINUTE)",
            tenant,
            nonce,
            admin
        );
        long task = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key=?",
            Long.class,
            tenant,
            nonce
        );
        db.update(
            "INSERT INTO outbox_event(event_id,tenant_id,task_id,routing_key,payload,created_at) VALUES(?,?,?,'document.parse',?,UTC_TIMESTAMP()-INTERVAL 10 MINUTE)",
            UUID.randomUUID().toString(),
            tenant,
            task,
            "{\"syntheticPrivateData\":\"private-model-key\"}"
        );
        db.update(
            "INSERT INTO task_delivery_recovery(tenant_id,task_id,attempt,delivery_status,retry_count,failure_kind) VALUES(?,?,0,'DEAD',3,'TRANSIENT')",
            tenant,
            task
        );
        for (String header : new String[] { "", "wrong" }) {
            var result = mvc
                .perform(get("/internal/v1/metrics").header("X-Internal-Secret", header))
                .andReturn();
            assertEquals(401, result.getResponse().getStatus());
            assertFalse(
                result.getResponse().getContentAsString().contains("lexflow_operational_records")
            );
        }
        var result = mvc
            .perform(get("/internal/v1/metrics").header("X-Internal-Secret", SECRET))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        assertTrue(result.getResponse().getContentType().startsWith("text/plain"));
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("lexflow_operational_database_available 1"));
        assertTrue(
            body.contains("lexflow_operational_records{category=\"outbox\",state=\"pending\"} 1")
        );
        assertTrue(
            body.contains("lexflow_operational_records{category=\"task\",state=\"queued\"} 1")
        );
        assertTrue(
            body.contains("lexflow_operational_records{category=\"delivery\",state=\"dead\"} 1")
        );
        String ageLine = body
            .lines()
            .filter(line ->
                line.startsWith(
                    "lexflow_operational_oldest_age_seconds{category=\"outbox\",state=\"pending\"}"
                )
            )
            .findFirst()
            .orElseThrow();
        assertTrue(Long.parseLong(ageLine.substring(ageLine.lastIndexOf(' ') + 1)) >= 599);
        assertTrue(body.contains("lexflow_audit_buffer_records 0"));
        for (String forbidden : new String[] {
            SECRET,
            "audit-owned-admin",
            "tenant_id",
            "actor_id",
            "traceId",
            "private-model-key",
            nonce,
            System.getenv("LEXFLOW_AUDIT_ISOLATED_PASSWORD"),
        })
            assertFalse(body.contains(forbidden));
        db.execute("RENAME TABLE outbox_event TO outbox_event_owned_offline");
        try {
            var unavailable = mvc
                .perform(get("/internal/v1/metrics").header("X-Internal-Secret", SECRET))
                .andReturn();
            String unavailableBody = unavailable.getResponse().getContentAsString();
            assertEquals(200, unavailable.getResponse().getStatus());
            assertTrue(unavailableBody.contains("lexflow_operational_database_available 0"));
            assertFalse(unavailableBody.contains("lexflow_operational_records{"));
        } finally {
            db.execute("RENAME TABLE outbox_event_owned_offline TO outbox_event");
        }
    }
}
