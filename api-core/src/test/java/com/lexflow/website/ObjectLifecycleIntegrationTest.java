package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lexflow.dao.CleanupTombstoneRepository;
import com.lexflow.dao.DocumentExportRepository;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.CaseService;
import com.lexflow.service.CleanupLifecycleService;
import com.lexflow.service.DocumentExportService;
import com.lexflow.service.DocumentService;
import com.lexflow.service.OrganizationService;
import com.lexflow.service.TaskService;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.website.biz.DocumentExportBiz;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Committed transactions and real private MinIO objects in a required disposable schema and bucket. */
@SpringBootTest(
    properties = {
        "scheduling.enabled=false",
        "lexflow.lifecycle.export-retention-days=1",
        "lexflow.lifecycle.cleanup-compact-after-days=1",
    }
)
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LIFECYCLE_INTEGRATION", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectLifecycleIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        String url = System.getenv("LEXFLOW_LIFECYCLE_DATABASE_URL"),
            bucket = System.getenv("LEXFLOW_LIFECYCLE_BUCKET");
        if (
            url == null ||
            !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/lexflow_lifecycle_[a-z0-9]+\\?.+")
        ) throw new IllegalStateException("生命周期检查必须使用独立临时库");
        if (
            bucket == null || !bucket.matches("lexflow-lifecycle-[a-z0-9]+")
        ) throw new IllegalStateException("生命周期检查必须使用独立私有bucket");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.url", () -> url);
        properties.add("lexflow.storage.bucket", () -> bucket);
    }

    @Autowired
    JdbcTemplate db;

    @Autowired
    OrganizationService organizations;

    @Autowired
    CaseService cases;

    @Autowired
    DocumentService documents;

    @Autowired
    DocumentExportService exports;

    @Autowired
    DocumentExportRepository jobs;

    @Autowired
    DocumentExportBiz biz;

    @Autowired
    TaskService tasks;

    @Autowired
    CleanupLifecycleService lifecycle;

    @Autowired
    CleanupTombstoneRepository tombstones;

    @SpyBean
    PrivateObjectStorage storage;

    Identity actor;
    long tenant, caseId, document, version, task;
    final Set<String> keys = new LinkedHashSet<>();

    @BeforeAll
    void installation() {
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class));
        tenant = Long.parseLong(
            organizations.initialize(
                "Lifecycle synthetic tenant",
                "lifecycle-synthetic-admin",
                "Lifecycle-only-test-2026!"
            )
        );
        long user = db.queryForObject(
            "SELECT id FROM sys_user WHERE username='lifecycle-synthetic-admin'",
            Long.class
        );
        actor = new Identity(user, tenant, 0, "Lifecycle tester");
        assertEquals(
            28,
            db.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type='SQL'",
                Integer.class
            )
        );
    }

    @BeforeEach
    void fixture() {
        caseId = Long.parseLong(
            cases
                .create(actor, "LIFE-" + UUID.randomUUID(), "Synthetic lifecycle case", "CIVIL", "")
                .id()
        );
        var doc = documents.create(
            actor,
            caseId,
            "固定版本文书",
            "仅供生命周期检查的合成正文\n版本一"
        );
        document = Long.parseLong(doc.id());
        version = Long.parseLong(doc.currentVersionId());
        task = Long.parseLong(
            exports.request(actor, caseId, document, version, UUID.randomUUID().toString()).taskId()
        );
    }

    @AfterEach
    void removeOwnedObjects() throws Exception {
        keys.addAll(
            db.queryForList(
                "SELECT object_key FROM document_export_artifact WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
        keys.addAll(
            db.queryForList(
                "SELECT object_key FROM export_cleanup_tombstone WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
        keys.addAll(
            db.queryForList(
                "SELECT object_key FROM file_version WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
        for (String key : keys) {
            assertTrue(key.startsWith("tenants/" + tenant + "/"));
            doCallRealMethod().when(storage).remove(key);
            storage.remove(key);
        }
    }

    DocumentExportService.Claim ready() throws Exception {
        var claim = exports.begin(tenant, task, 0, 1);
        assertNotNull(claim);
        put(claim.objectKey(), claim.bytes());
        assertTrue(exports.complete(claim));
        return claim;
    }

    void put(String key, byte[] bytes) throws Exception {
        keys.add(key);
        var file = Files.createTempFile("lexflow-lifecycle-", ".txt");
        try {
            Files.write(file, bytes);
            storage.put(key, file, "text/plain;charset=UTF-8");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    void pastDeadline() {
        db.update(
            "UPDATE document_export_job SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE tenant_id=? AND task_id=?",
            tenant,
            task
        );
    }

    void absent(String key) {
        assertThrows(Exception.class, () -> {
            try (var ignored = storage.open(key)) {
                throw new AssertionError("对象仍可读取");
            }
        });
    }

    @Test
    void deadlineStartsAtPublicationAndHistoricalZeroRemainsDownloadable() throws Exception {
        assertEquals(
            1,
            db.queryForObject(
                "SELECT retention_days FROM document_export_job WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                task
            )
        );
        assertNull(exports.view(actor, task).expiresAt());
        ready();
        var view = exports.view(actor, task);
        assertFalse(view.expired());
        assertTrue(Instant.parse(view.expiresAt()).isAfter(Instant.now().plusSeconds(86000)));
        assertNotNull(exports.download(actor, task));
        // The migration's historical default is zero. A zero policy must not invent a deadline.
        var second = exports.request(
            actor,
            caseId,
            document,
            version,
            UUID.randomUUID().toString()
        );
        long historical = Long.parseLong(second.taskId());
        db.update(
            "UPDATE document_export_job SET retention_days=0 WHERE tenant_id=? AND task_id=?",
            tenant,
            historical
        );
        var claim = exports.begin(tenant, historical, 0, 1);
        put(claim.objectKey(), claim.bytes());
        assertTrue(exports.complete(claim));
        assertNull(exports.view(actor, historical).expiresAt());
        assertFalse(exports.view(actor, historical).expired());
        assertNotNull(exports.download(actor, historical));
    }

    @Test
    void deadlineRevokesDownloadsBeforeSweepAndPreservesSourceVersion() throws Exception {
        var claim = ready();
        var before = exports.download(actor, task);
        pastDeadline();
        assertEquals(
            409,
            assertThrows(BusinessException.class, () -> exports.download(actor, task)).getCode()
        );
        assertThrows(BusinessException.class, () -> exports.auditDownload(actor, task, before));
        assertTrue(exports.view(actor, task).expired());
        assertEquals("SUCCEEDED", exports.view(actor, task).status());
        assertThrows(AccessDenied.class, () ->
            exports.view(new Identity(actor.userId(), tenant + 1, 0, "Other"), task)
        );
        assertTrue(exports.expireResult(tenant, task));
        assertFalse(exports.expireResult(tenant, task));
        assertNull(jobs.get(tenant, task).objectKey());
        assertEquals(4, exports.view(actor, task).stateVersion());
        assertEquals(
            version,
            db.queryForObject(
                "SELECT current_version_id FROM document WHERE tenant_id=? AND id=?",
                Long.class,
                tenant,
                document
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:export:expired' AND object_id=?",
                Integer.class,
                tenant,
                Long.toString(task)
            )
        );
        biz.maintain();
        absent(claim.objectKey());
    }

    @Test
    void failedAuditCommitRollsBackExpiryAndKeepsRegisteredPrivateObject() throws Exception {
        var claim = ready();
        pastDeadline();
        db.execute("RENAME TABLE audit_log TO lifecycle_owned_audit_log");
        try {
            assertThrows(Exception.class, () -> exports.expireResult(tenant, task));
        } finally {
            db.execute("RENAME TABLE lifecycle_owned_audit_log TO audit_log");
        }
        assertEquals(claim.objectKey(), jobs.get(tenant, task).objectKey());
        assertNull(
            db.queryForObject(
                "SELECT expired_at FROM document_export_job WHERE tenant_id=? AND task_id=?",
                java.sql.Timestamp.class,
                tenant,
                task
            )
        );
        assertEquals(3, exports.view(actor, task).stateVersion());
        try (var in = storage.open(claim.objectKey())) {
            assertArrayEquals(claim.bytes(), in.readAllBytes());
        }
        assertTrue(exports.expireResult(tenant, task));
        biz.maintain();
        absent(claim.objectKey());
    }

    @Test
    void expiredObjectDeleteFailureIsDurableAndRetriesWithoutRepublishing() throws Exception {
        var claim = ready();
        pastDeadline();
        doThrow(new IOException("synthetic sensitive endpoint must not be saved"))
            .when(storage)
            .remove(claim.objectKey());
        biz.maintain();
        assertNull(jobs.get(tenant, task).objectKey());
        assertEquals(
            "IOException",
            db.queryForObject(
                "SELECT cleanup_error FROM document_export_artifact WHERE token=?",
                String.class,
                claim.token()
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT cleanup_attempts FROM document_export_artifact WHERE token=?",
                Integer.class,
                claim.token()
            )
        );
        doCallRealMethod().when(storage).remove(claim.objectKey());
        db.update(
            "UPDATE document_export_artifact SET next_cleanup_at=NOW(6) WHERE token=?",
            claim.token()
        );
        biz.maintain();
        absent(claim.objectKey());
        assertTrue(exports.view(actor, task).expired());
    }

    long file(String state, boolean old) throws Exception {
        String name = "LIFE-" + UUID.randomUUID();
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,?,?,?)",
            tenant,
            caseId,
            name,
            state,
            actor.userId()
        );
        long id = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND display_name=?",
            Long.class,
            tenant,
            name
        );
        String key = "tenants/" + tenant + "/cases/" + caseId + "/" + id + "/v1";
        byte[] bytes = "synthetic obsolete file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        put(key, bytes);
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            tenant,
            id,
            key,
            DocumentExportService.sha256(bytes),
            bytes.length
        );
        db.update(
            "INSERT INTO file_cleanup_job(tenant_id,file_id,object_key,status,completed_at) VALUES(?,?,?,'DONE',TIMESTAMPADD(DAY,?,NOW(6)))",
            tenant,
            id,
            key,
            old ? -2 : 0
        );
        return id;
    }

    @Test
    void fileCompactionKeepsLateWriteTombstoneAndNeverDeletesAvailableFiles() throws Exception {
        long obsolete = file("DELETED", true),
            available = file("AVAILABLE", true),
            young = file("FAILED", false);
        assertTrue(lifecycle.compactFile());
        assertFalse(lifecycle.compactFile());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_cleanup_job WHERE tenant_id=? AND file_id=?",
                Integer.class,
                tenant,
                obsolete
            )
        );
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_cleanup_job WHERE tenant_id=? AND file_id IN (?,?)",
                Integer.class,
                tenant,
                available,
                young
            )
        );
        String key = db.queryForObject(
            "SELECT object_key FROM file_cleanup_tombstone WHERE tenant_id=? AND file_id=?",
            String.class,
            tenant,
            obsolete
        );
        assertTrue(lifecycle.review(true));
        absent(key);
        put(key, "late upload".getBytes());
        db.update(
            "UPDATE file_cleanup_tombstone SET next_review_at=NOW(6) WHERE tenant_id=? AND file_id=?",
            tenant,
            obsolete
        );
        assertTrue(lifecycle.review(true));
        absent(key);
        put(key, "late upload while owner changed".getBytes());
        db.update(
            "UPDATE file_object SET status='AVAILABLE' WHERE tenant_id=? AND id=?",
            tenant,
            obsolete
        );
        db.update(
            "UPDATE file_cleanup_tombstone SET next_review_at=NOW(6) WHERE tenant_id=? AND file_id=?",
            tenant,
            obsolete
        );
        assertFalse(lifecycle.review(true));
        try (var in = storage.open(key)) {
            assertTrue(in.read() > 0);
        }
    }

    @Test
    void compactedCancelledExportRemovesLateWritesButPreservesPublishedArtifact() throws Exception {
        var old = exports.begin(tenant, task, 0, 1);
        put(old.objectKey(), old.bytes());
        tasks.requestCancel(actor, task);
        storage.remove(old.objectKey());
        jobs.cleaned(
            new DocumentExportRepository.Artifact(old.token(), tenant, task, old.objectKey()),
            null
        );
        // Recent daily reviews must not postpone compaction beyond the first successful deletion age.
        db.update(
            "UPDATE document_export_artifact SET first_cleaned_at=TIMESTAMPADD(DAY,-2,NOW(6)),last_cleaned_at=NOW(6),next_cleanup_at=NOW(6) WHERE token=?",
            old.token()
        );
        assertTrue(lifecycle.compactExport());
        assertNull(jobs.objectKey(old.token(), tenant, task));
        assertFalse(exports.complete(old));
        put(old.objectKey(), old.bytes());
        assertTrue(lifecycle.review(false));
        absent(old.objectKey());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM export_cleanup_tombstone WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                task
            )
        );
        var published = exports.request(
            actor,
            caseId,
            document,
            version,
            UUID.randomUUID().toString()
        );
        long current = Long.parseLong(published.taskId());
        var claim = exports.begin(tenant, current, 0, 1);
        put(claim.objectKey(), claim.bytes());
        assertTrue(exports.complete(claim));
        db.update(
            "UPDATE document_export_artifact SET first_cleaned_at=TIMESTAMPADD(DAY,-2,NOW(6)),last_cleaned_at=NOW(6),next_cleanup_at=NOW(6) WHERE token=?",
            claim.token()
        );
        assertFalse(lifecycle.compactExport());
        assertNotNull(jobs.objectKey(claim.token(), tenant, current));
        assertNotNull(exports.download(actor, current));
    }

    @Test
    void concurrentExpiryCommitsOneAuditAndOneStateVersion() throws Exception {
        ready();
        pastDeadline();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Boolean> work = () -> {
                start.await();
                return exports.expireResult(tenant, task);
            };
            var a = executor.submit(work);
            var b = executor.submit(work);
            start.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(4, exports.view(actor, task).stateVersion());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:export:expired' AND object_id=?",
                Integer.class,
                tenant,
                Long.toString(task)
            )
        );
    }

    @Test
    void tombstoneDeleteFailureKeepsExactKeyAndRetries() throws Exception {
        long id = file("FAILED", true);
        assertTrue(lifecycle.compactFile());
        String key = db.queryForObject(
            "SELECT object_key FROM file_cleanup_tombstone WHERE tenant_id=? AND file_id=?",
            String.class,
            tenant,
            id
        );
        doThrow(new IOException("sensitive server address")).when(storage).remove(key);
        assertTrue(lifecycle.review(true));
        assertEquals(
            "IOException",
            db.queryForObject(
                "SELECT last_error FROM file_cleanup_tombstone WHERE tenant_id=? AND file_id=?",
                String.class,
                tenant,
                id
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_cleanup_tombstone WHERE tenant_id=? AND file_id=? AND next_review_at>TIMESTAMPADD(SECOND,50,NOW(6))",
                Integer.class,
                tenant,
                id
            )
        );
        doCallRealMethod().when(storage).remove(key);
        db.update(
            "UPDATE file_cleanup_tombstone SET next_review_at=NOW(6) WHERE tenant_id=? AND file_id=?",
            tenant,
            id
        );
        assertTrue(lifecycle.review(true));
        absent(key);
        assertNull(
            db.queryForObject(
                "SELECT last_error FROM file_cleanup_tombstone WHERE tenant_id=? AND file_id=?",
                String.class,
                tenant,
                id
            )
        );
    }
}
