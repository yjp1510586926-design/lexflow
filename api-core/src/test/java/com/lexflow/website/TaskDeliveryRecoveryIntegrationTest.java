package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.TaskResultService;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import com.lexflow.website.core.client.IndexRecoveryEligibilityClient;
import com.lexflow.website.core.config.RabbitMqConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in real MySQL/Redis checks. Synthetic source metadata needs no object or paid model call. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class TaskDeliveryRecoveryIntegrationTest {

    static final long TENANT = 910000215L,
        OTHER = 910000216L,
        OWNER = 910000315L,
        PEER = 910000316L;
    static final String TEXT = "合成恢复验收材料：验收后十日内付款。",
        HASH = "d".repeat(64);
    static final String PASSWORD = "Recovery-test-only-password!",
        PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    final Identity admin = new Identity(TENANT, TENANT, 0, "Recovery Admin");
    final Identity owner = new Identity(OWNER, TENANT, 0, "Recovery Owner");
    final Identity peer = new Identity(PEER, TENANT, 0, "Recovery Peer");
    final Identity outsider = new Identity(OTHER, OTHER, 0, "Other Admin");

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate db;

    @Autowired
    TaskDeliveryRecoveryBiz recovery;

    @MockitoBean
    IndexRecoveryEligibilityClient eligibility;

    @Autowired
    TaskService tasks;

    @Autowired
    TaskResultService results;

    @Autowired
    CaseService cases;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    KnowledgeIndexService indexes;

    @Autowired
    AiConfigurationService configurations;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    org.springframework.session.SessionRepository<?> sessionRepository;

    long caseId;
    String address;
    final List<Session> sessions = new ArrayList<>();
    final Set<String> requestTraces = new LinkedHashSet<>();
    final Set<String> ownedSessionIds = new LinkedHashSet<>();

    record Source(long file, long version, Long base) {}

    record IndexFixture(long task, long base, long document, Source source, Identity creator) {}

    record Session(Cookie cookie, String csrf) {}

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry properties) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        properties.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(key));
    }

    @BeforeEach
    void fixtures() {
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id IN (?,?)",
                Integer.class,
                TENANT,
                OTHER
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id IN (?,?,?,?)",
                Integer.class,
                TENANT,
                OTHER,
                OWNER,
                PEER
            )
        );
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'delivery-recovery-only'),(?,'delivery-recovery-other')",
            TENANT,
            OTHER
        );
        for (long user : List.of(TENANT, OTHER, OWNER, PEER)) {
            long tenant = user == OTHER ? OTHER : TENANT;
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,?,'Recovery Tester')",
                user,
                "recovery-only-" + user,
                PASSWORD_HASH
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            String role =
                user == TENANT || user == OTHER
                    ? "TENANT_ADMIN"
                    : user == OWNER
                      ? "LAWYER"
                      : "ASSISTANT";
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Recovery fixture')",
                user,
                tenant,
                role
            );
            db.update(
                "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                user,
                user
            );
            db.update(
                "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
                tenant,
                user
            );
        }
        caseId = Long.parseLong(
            cases
                .create(admin, "RECOVERY-" + request(), "Synthetic recovery case", "CIVIL", "")
                .id()
        );
        cases.addMember(admin, caseId, OWNER, "EDITOR");
        cases.addMember(admin, caseId, PEER, "EDITOR");
        configurations.save(
            TENANT,
            "EMBEDDING",
            "fixture",
            "https://models.example.test/v1",
            "recovery-embedding",
            "fake-recovery-key"
        );
        String nonce = request().replace("-", "");
        address =
            "fd00::" +
            nonce.substring(0, 4) +
            ":" +
            nonce.substring(4, 8) +
            ":" +
            nonce.substring(8, 12) +
            ":" +
            nonce.substring(12, 16);
        when(eligibility.check(anyLong(), any(KnowledgeIndexService.IndexInput.class))).thenReturn(
            "SAFE"
        );
    }

    @AfterEach
    void cleanOwnedHttpState() throws Exception {
        try {
            for (Session session : sessions) {
                perform(
                    post("/api/v1/auth/logout")
                        .cookie(session.cookie())
                        .header("X-CSRF-TOKEN", session.csrf())
                ).andExpect(status().isOk());
            }
        } finally {
            // Failed anonymous requests and pre-login CSRF requests can also create Redis sessions.
            // Delete only IDs observed in this test's own Set-Cookie headers, including failed test paths.
            for (String id : ownedSessionIds) {
                sessionRepository.deleteById(id);
                assertNull(sessionRepository.findById(id));
            }
            if (address != null) redis.delete("lexflow:login:ip:" + address);
        }
    }

    /** The business transaction must release its audit rows before deleting independent request audits. */
    @AfterTransaction
    void clearOwnedRequestAudits() {
        for (String trace : requestTraces)
            db.update(
                "DELETE FROM audit_log WHERE object_type='http_request' AND trace_id=? AND (tenant_id IS NULL OR tenant_id IN (?,?))",
                trace,
                TENANT,
                OTHER
            );
    }

    String request() {
        return UUID.randomUUID().toString();
    }

    Source source(boolean tenantScope) {
        Long base = tenantScope
            ? Long.parseLong(knowledge.createTenant(admin, "Public recovery " + request()).id())
            : null;
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,scope,knowledge_base_id,display_name,status,created_by) VALUES(?,?,?,?,?,'AVAILABLE',?)",
            TENANT,
            tenantScope ? null : caseId,
            tenantScope ? "TENANT" : "CASE",
            base,
            "recovery-" + request() + ".txt",
            TENANT
        );
        long file = db.queryForObject(
            "SELECT MAX(id) FROM file_object WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            TENANT,
            file,
            "synthetic-recovery-only/" + request(),
            HASH,
            TEXT.getBytes(StandardCharsets.UTF_8).length
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
        return new Source(file, version, base);
    }

    long parse(Identity actor, Source source) {
        return Long.parseLong(
            source.base() == null
                ? tasks
                      .submitTask(actor, caseId, "DOCUMENT_PARSE", source.version(), request())
                      .id()
                : tasks.submitKnowledgeParse(actor, source.base(), source.version(), request()).id()
        );
    }

    void completeParse(long task, int attempt, int version) {
        results.applyResult(
            task,
            attempt,
            version,
            true,
            null,
            null,
            TEXT,
            HASH,
            List.of(new TaskResultService.SourceSegment("PARAGRAPH", 1, 0, TEXT.length()))
        );
    }

    IndexFixture index(boolean tenantScope) {
        var source = source(tenantScope);
        Identity creator = tenantScope ? admin : owner;
        long parse = parse(creator, source);
        completeParse(parse, 0, 1);
        long base = tenantScope
            ? source.base()
            : Long.parseLong(knowledge.create(admin, caseId, "Private recovery " + request()).id());
        long document = Long.parseLong(knowledge.prepare(creator, base, parse).id());
        long task = Long.parseLong(indexes.request(creator, base, document).taskId());
        return new IndexFixture(task, base, document, source, creator);
    }

    void revoke(long role, String permission) {
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code=?",
            TENANT,
            role,
            permission
        );
    }

    void restore(long role, String permission) {
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,?)",
            TENANT,
            role,
            permission
        );
    }

    int outbox(long task) {
        return db.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            Integer.class,
            TENANT,
            task
        );
    }

    void state(long task, String status, int attempt, int version) {
        var row = db.queryForMap(
            "SELECT status,attempt,state_version FROM async_task WHERE tenant_id=? AND id=?",
            TENANT,
            task
        );
        assertEquals(status, row.get("status"));
        assertEquals(attempt, ((Number) row.get("attempt")).intValue());
        assertEquals(version, ((Number) row.get("state_version")).intValue());
    }

    void denied(Runnable call) {
        var failure = assertThrows(RuntimeException.class, call::run);
        assertTrue(
            failure instanceof AccessDenied ||
                (failure instanceof BusinessException &&
                    ((BusinessException) failure).getCode() == 403),
            () -> "Expected access refusal, got " + failure
        );
    }

    void unavailable(Runnable call) {
        var failure = assertThrows(RuntimeException.class, call::run);
        assertTrue(
            failure instanceof AccessDenied ||
                (failure instanceof BusinessException &&
                    Set.of(403, 404).contains(((BusinessException) failure).getCode())),
            () -> "Expected unavailable source, got " + failure
        );
    }

    void conflict(Runnable call) {
        assertEquals(409, assertThrows(BusinessException.class, call::run).getCode());
    }

    void dead(long task, String kind) {
        recovery.deadLetter(TENANT, task, 0, 3, kind);
    }

    ObjectNode event(long task, int attempt) throws Exception {
        for (String body : db.queryForList(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            String.class,
            TENANT,
            task
        )) {
            var node = json.readTree(body);
            if (node.isTextual()) node = json.readTree(node.asText());
            if (
                node.isObject() && node.path("attempt").asInt() == attempt
            ) return (ObjectNode) node;
        }
        throw new AssertionError("Missing owned outbox for task=" + task + " attempt=" + attempt);
    }

    void noLedger(long task) {
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                task
            )
        );
    }

    @Test
    void deadLetterIsRecordedOnceAndOldResultsCannotPublish() {
        long task = parse(owner, source(false));
        dead(task, "TRANSIENT");
        state(task, "WAITING_HUMAN", 0, 2);
        var view = recovery.view(owner, task);
        assertEquals("DEAD", view.deliveryStatus());
        assertEquals(3, view.retryCount());
        assertEquals("TRANSIENT", view.failureKind());
        assertNotNull(view.deadLetteredAt());
        assertTrue(view.canRecover());
        assertEquals(2, view.stateVersion());
        recovery.deadLetter(TENANT, task, 0, 3, "TRANSIENT");
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals("TRANSIENT", recovery.view(owner, task).failureKind());
        assertEquals(1, outbox(task));
        completeParse(task, 0, 1);
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_parse_result WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                task
            )
        );
    }

    @Test
    void staleTenantAttemptAndTerminalTasksDoNotCreateRecoveryRecords() {
        long task = parse(owner, source(false));
        recovery.deadLetter(OTHER, task, 0, 3, "TRANSIENT");
        recovery.deadLetter(TENANT, task, 1, 3, "TRANSIENT");
        state(task, "QUEUED", 0, 1);
        assertEquals("NONE", recovery.view(owner, task).deliveryStatus());
        completeParse(task, 0, 1);
        state(task, "SUCCEEDED", 0, 2);
        recovery.deadLetter(TENANT, task, 0, 3, "TRANSIENT");
        state(task, "SUCCEEDED", 0, 2);
        assertEquals("NONE", recovery.view(owner, task).deliveryStatus());
        assertEquals(1, outbox(task));
    }

    @Test
    void parseRecoveryBindsANewAttemptAndSingleOutboxAndSurvivesDuplicateIntent() throws Exception {
        long task = parse(owner, source(false));
        dead(task, "TRANSIENT");
        String intent = request();
        recovery.recover(owner, task, 2, intent, false);
        state(task, "QUEUED", 1, 3);
        assertEquals("RESOLVED", recovery.view(owner, task).deliveryStatus());
        assertEquals(2, outbox(task));
        recovery.recover(owner, task, 2, intent, false);
        state(task, "QUEUED", 1, 3);
        assertEquals(2, outbox(task));
        conflict(() -> recovery.recover(owner, task, 2, request(), false));
        var bodies = db.queryForList(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            String.class,
            TENANT,
            task
        );
        var resumed = bodies
            .stream()
            .map(body -> {
                try {
                    return json.readTree(body);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            })
            .filter(body -> body.path("attempt").asInt() == 1)
            .findFirst()
            .orElseThrow();
        assertEquals(Long.toString(task), resumed.path("taskId").asText());
        assertEquals("DOCUMENT_PARSE", resumed.path("type").asText());
        assertEquals("CASE", resumed.path("scope").asText());
        assertEquals(Long.toString(caseId), resumed.path("caseId").asText());
        assertEquals("1.0", resumed.path("schemaVersion").asText());
        assertFalse(resumed.toString().contains("apiKey"));
        recovery.deadLetter(TENANT, task, 0, 3, "TRANSIENT");
        completeParse(task, 0, 1);
        state(task, "QUEUED", 1, 3);
        completeParse(task, 1, 3);
        state(task, "SUCCEEDED", 1, 4);
        recovery.recover(owner, task, 2, intent, false);
        state(task, "SUCCEEDED", 1, 4);
        assertEquals(2, outbox(task));
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_parse_result WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                task
            )
        );
        verifyNoInteractions(eligibility);
    }

    @Test
    void aReusedRecoveryRequestNeverRestartsALaterDeadLetter() {
        long task = parse(owner, source(false));
        dead(task, "TRANSIENT");
        String first = request();
        recovery.recover(owner, task, 2, first, false);
        recovery.deadLetter(TENANT, task, 1, 3, "TRANSIENT");
        state(task, "WAITING_HUMAN", 1, 4);
        recovery.recover(owner, task, 2, first, false);
        state(task, "WAITING_HUMAN", 1, 4);
        assertEquals(2, outbox(task));
        recovery.recover(owner, task, 4, request(), false);
        state(task, "QUEUED", 2, 5);
        assertEquals(3, outbox(task));
        recovery.deadLetter(TENANT, task, 1, 3, "TRANSIENT");
        state(task, "QUEUED", 2, 5);
    }

    @Test
    void recoveryIntentCannotBeReusedForAnotherTaskOrAnotherState() {
        long first = parse(owner, source(false)),
            second = parse(owner, source(false));
        dead(first, "TRANSIENT");
        dead(second, "TRANSIENT");
        String intent = request();
        recovery.recover(owner, first, 2, intent, false);
        conflict(() -> recovery.recover(owner, second, 2, intent, false));
        conflict(() -> recovery.recover(owner, first, 3, intent, false));
        state(first, "QUEUED", 1, 3);
        state(second, "WAITING_HUMAN", 0, 2);
        assertEquals(2, outbox(first));
        assertEquals(1, outbox(second));
    }

    @Test
    void crossTenantPeerAndMissingTaskRightsCannotReadOrRecover() {
        long task = parse(owner, source(false));
        dead(task, "PERMANENT");
        denied(() -> recovery.view(outsider, task));
        denied(() -> recovery.recover(outsider, task, 2, request(), false));
        denied(() -> recovery.view(peer, task));
        denied(() -> recovery.recover(peer, task, 2, request(), false));
        revoke(OWNER, "task:cancel");
        assertFalse(recovery.view(owner, task).canRecover());
        denied(() -> recovery.recover(owner, task, 2, request(), false));
        restore(OWNER, "task:cancel");
        revoke(OWNER, "task:read");
        denied(() -> recovery.view(owner, task));
        denied(() -> recovery.recover(owner, task, 2, request(), false));
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(task));
    }

    @Test
    void adminRecoveryRechecksCurrentCreatorAndSourceRights() {
        var source = source(false);
        long task = parse(owner, source);
        dead(task, "TRANSIENT");
        revoke(OWNER, "file:read");
        denied(() -> recovery.recover(admin, task, 2, request(), false));
        restore(OWNER, "file:read");
        db.update(
            "UPDATE tenant_user SET status='DISABLED' WHERE tenant_id=? AND user_id=?",
            TENANT,
            OWNER
        );
        denied(() -> recovery.recover(admin, task, 2, request(), false));
        db.update(
            "UPDATE tenant_user SET status='ACTIVE' WHERE tenant_id=? AND user_id=?",
            TENANT,
            OWNER
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            source.file()
        );
        unavailable(() -> recovery.recover(admin, task, 2, request(), false));
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(task));
    }

    @Test
    void archivedCaseRemainsReadableButItsTasksCannotBeResumed() {
        long task = parse(owner, source(false));
        dead(task, "TRANSIENT");
        db.update(
            "UPDATE case_info SET status='ARCHIVED',archived_at=NOW(6) WHERE tenant_id=? AND id=?",
            TENANT,
            caseId
        );
        assertFalse(recovery.view(owner, task).canRecover());
        denied(() -> recovery.recover(owner, task, 2, request(), false));
        denied(() -> recovery.recover(admin, task, 2, request(), false));
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(task));
    }

    @Test
    void tenantParseUsesItsOwnScopeAndNeverRequiresAnUnrelatedCase() throws Exception {
        var source = source(true);
        long task = parse(admin, source);
        dead(task, "TRANSIENT");
        revoke(TENANT, "case:read");
        revoke(TENANT, "file:read");
        assertTrue(recovery.view(admin, task).canRecover());
        recovery.recover(admin, task, 2, request(), false);
        state(task, "QUEUED", 1, 3);
        var body = db
            .queryForList(
                "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                String.class,
                TENANT,
                task
            )
            .stream()
            .map(value -> {
                try {
                    return json.readTree(value);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            })
            .filter(value -> value.path("attempt").asInt() == 1)
            .findFirst()
            .orElseThrow();
        assertEquals("TENANT", body.path("scope").asText());
        assertTrue(body.path("caseId").isNull());
        assertEquals(Long.toString(source.base()), body.path("knowledgeBaseId").asText());
        recovery.deadLetter(TENANT, task, 1, 3, "TRANSIENT");
        revoke(TENANT, "knowledge:manage");
        denied(() -> recovery.view(admin, task));
        denied(() -> recovery.recover(admin, task, 4, request(), false));
        state(task, "WAITING_HUMAN", 1, 4);
        assertEquals(2, outbox(task));
    }

    @Test
    void archivedPublicBaseAndDeletedPublicSourceCannotBeRecovered() {
        var source = source(true);
        long task = parse(admin, source);
        dead(task, "PERMANENT");
        db.update(
            "UPDATE knowledge_base SET status='ARCHIVED' WHERE tenant_id=? AND id=?",
            TENANT,
            source.base()
        );
        denied(() -> recovery.view(admin, task));
        denied(() -> recovery.recover(admin, task, 2, request(), false));
        db.update(
            "UPDATE knowledge_base SET status='ACTIVE' WHERE tenant_id=? AND id=?",
            TENANT,
            source.base()
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            source.file()
        );
        denied(() -> recovery.view(admin, task));
        denied(() -> recovery.recover(admin, task, 2, request(), false));
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(task));
    }

    @Test
    void readOnlyIndexEligibilityRejectionDoesNotPoisonTheEnclosingTransaction() {
        var fixture = index(false);
        dead(fixture.task(), "TRANSIENT");
        db.update(
            "UPDATE knowledge_index_job SET execution_protocol=1 WHERE tenant_id=? AND task_id=?",
            TENANT,
            fixture.task()
        );
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
            transactionManager
        );
        transaction.execute(status -> {
            assertFalse(status.isRollbackOnly());
            var view = recovery.view(owner, fixture.task());
            assertFalse(view.canRecover());
            assertFalse(
                status.isRollbackOnly(),
                "A read-only eligibility rejection must not mark the outer transaction rollback-only"
            );
            return null;
        });
        assertEquals(1, outbox(fixture.task()));
        state(fixture.task(), "WAITING_HUMAN", 0, 2);
    }

    @Test
    void uncertainModelRequestsAreNeverResentByRecovery() {
        var fixture = index(false);
        dead(fixture.task(), "UNCERTAIN");
        assertFalse(recovery.view(owner, fixture.task()).canRecover());
        assertNotNull(recovery.view(owner, fixture.task()).recoveryMessage());
        conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
        state(fixture.task(), "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(fixture.task()));
    }

    @Test
    void repeatedDeadLettersCanEscalateUncertaintyAndNeverDowngradeIt() {
        var fixture = index(false);
        dead(fixture.task(), "TRANSIENT");
        assertTrue(recovery.view(owner, fixture.task()).canRecover());
        recovery.deadLetter(TENANT, fixture.task(), 0, 3, "UNCERTAIN");
        assertEquals("UNCERTAIN", recovery.view(owner, fixture.task()).failureKind());
        assertFalse(recovery.view(owner, fixture.task()).canRecover());
        recovery.deadLetter(TENANT, fixture.task(), 0, 3, "TRANSIENT");
        assertEquals("UNCERTAIN", recovery.view(owner, fixture.task()).failureKind());
        conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
        assertEquals(1, outbox(fixture.task()));
    }

    @Test
    void absentOrUnknownFailureClassificationIsUncertainForIndexTasks() {
        for (String kind : new String[] { null, "UNKNOWN" }) {
            var fixture = index(false);
            dead(fixture.task(), kind);
            var view = recovery.view(owner, fixture.task());
            assertEquals("UNCERTAIN", view.failureKind());
            assertFalse(view.canRecover());
            conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
            state(fixture.task(), "WAITING_HUMAN", 0, 2);
            assertEquals(1, outbox(fixture.task()));
        }
    }

    @Test
    void cancellationAfterDeadLetterPreventsRecoveryAndLateResults() {
        for (boolean tenantScope : List.of(false, true)) {
            long task = parse(tenantScope ? admin : owner, source(tenantScope));
            dead(task, "TRANSIENT");
            tasks.requestCancel(tenantScope ? admin : owner, task);
            var cancelled = db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                TENANT,
                task
            );
            assertTrue(Set.of("CANCELLED", "CANCEL_REQUESTED").contains(cancelled));
            conflict(() ->
                recovery.recover(tenantScope ? admin : owner, task, 2, request(), false)
            );
            recovery.deadLetter(TENANT, task, 0, 3, "TRANSIENT");
            completeParse(task, 0, 1);
            assertEquals(
                cancelled,
                db.queryForObject(
                    "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                    String.class,
                    TENANT,
                    task
                )
            );
            assertEquals(1, outbox(task));
        }
        var fixture = index(false);
        dead(fixture.task(), "TRANSIENT");
        tasks.requestCancel(owner, fixture.task());
        state(fixture.task(), "CANCELLED", 0, 3);
        assertNull(
            db.queryForObject(
                "SELECT pending_index_task_id FROM knowledge_document WHERE tenant_id=? AND id=?",
                Long.class,
                TENANT,
                fixture.document()
            )
        );
        conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
        recovery.deadLetter(TENANT, fixture.task(), 0, 3, "TRANSIENT");
        state(fixture.task(), "CANCELLED", 0, 3);
    }

    @Test
    void indexRecoveryRequiresCostConfirmationAndPreservesRealScopeAndInput() throws Exception {
        for (boolean tenantScope : List.of(false, true)) {
            var fixture = index(tenantScope);
            long task = fixture.task();
            dead(task, "TRANSIENT");
            assertEquals(
                400,
                assertThrows(BusinessException.class, () ->
                    recovery.recover(fixture.creator(), task, 2, request(), false)
                ).getCode()
            );
            state(task, "WAITING_HUMAN", 0, 2);
            assertEquals(1, outbox(task));
            recovery.recover(fixture.creator(), task, 2, request(), true);
            state(task, "QUEUED", 1, 3);
            var input = indexes.input(task, 1, 3);
            assertEquals(tenantScope ? "TENANT" : "CASE", input.scope());
            assertEquals(Long.toString(fixture.base()), input.knowledgeBaseId());
            assertEquals(Long.toString(fixture.source().version()), input.fileVersionId());
            assertEquals(1, input.chunks().size());
            assertEquals(TEXT, input.chunks().getFirst().text());
            conflict(() -> indexes.input(task, 0, 1));
            assertFalse(
                indexes.complete(task, 0, 1, true, "ignored-old-collection", 4, 1, "old-model")
            );
            String identity = json.writeValueAsString(
                List.of(input.provider(), input.baseUrl(), input.model())
            );
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                identity.getBytes(StandardCharsets.UTF_8)
            );
            String collection =
                "lexflow_chunks_" + HexFormat.of().formatHex(digest, 0, 8) + "_4_v1";
            assertTrue(
                indexes.complete(task, 1, 3, true, collection, 4, input.chunks().size(), identity)
            );
            state(task, "SUCCEEDED", 1, 4);
            assertEquals(
                "ACTIVE",
                db.queryForObject(
                    "SELECT status FROM knowledge_document WHERE tenant_id=? AND id=?",
                    String.class,
                    TENANT,
                    fixture.document()
                )
            );
            assertEquals(2, outbox(task));
        }
    }

    @Test
    void indexRecoveryCannotChangeItsBoundEmbeddingModelOrBypassManagePermission() {
        var fixture = index(false);
        dead(fixture.task(), "TRANSIENT");
        revoke(OWNER, "knowledge:manage");
        denied(() -> recovery.recover(admin, fixture.task(), 2, request(), true));
        restore(OWNER, "knowledge:manage");
        configurations.save(
            TENANT,
            "EMBEDDING",
            "fixture",
            "https://models.example.test/v1",
            "different-embedding",
            "another-fake-key"
        );
        conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
        state(fixture.task(), "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(fixture.task()));
    }

    @Test
    void legacyIndexExecutionProtocolCannotBeRecoveredEvenWithSafeEligibility() {
        var fixture = index(false);
        db.update(
            "UPDATE knowledge_index_job SET execution_protocol=1 WHERE tenant_id=? AND task_id=?",
            TENANT,
            fixture.task()
        );
        for (String status : List.of("QUEUED", "RUNNING")) {
            db.update(
                "UPDATE async_task SET status=? WHERE tenant_id=? AND id=?",
                status,
                TENANT,
                fixture.task()
            );
            conflict(() -> indexes.input(fixture.task(), 0, 1));
            state(fixture.task(), status, 0, 1);
        }
        dead(fixture.task(), "TRANSIENT");
        assertFalse(recovery.view(owner, fixture.task()).canRecover());
        conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
        state(fixture.task(), "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(fixture.task()));
        verifyNoInteractions(eligibility);
    }

    @Test
    void collectorBindsCaseParseToItsRealOutboxFromMainAndDeadQueues() throws Exception {
        long task = parse(owner, source(false));
        var original = event(task, 0);
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.QUEUE_DOCUMENT_PARSE, original, 3, "TRANSIENT")
        );
        state(task, "WAITING_HUMAN", 0, 2);
        assertTrue(
            recovery.acceptDeadLetter(
                RabbitMqConfig.DLQ_DOCUMENT_PARSE,
                original.deepCopy(),
                3,
                "TRANSIENT"
            )
        );
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(task));
        assertEquals("DEAD", recovery.view(owner, task).deliveryStatus());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='task:delivery:dead'",
                Integer.class,
                TENANT,
                Long.toString(task)
            )
        );
    }

    @Test
    void collectorRejectsTamperedImmutableEventFieldsWithoutChangingTaskOrLedger()
        throws Exception {
        long task = parse(owner, source(false));
        var original = event(task, 0);
        for (String field : List.of(
            "eventId",
            "schemaVersion",
            "tenantId",
            "taskId",
            "resourceVersionId",
            "scope",
            "caseId",
            "knowledgeBaseId",
            "type",
            "attempt",
            "stateVersion"
        )) {
            var forged = original.deepCopy();
            switch (field) {
                case "eventId" -> forged.put(field, request());
                case "schemaVersion" -> forged.put(field, "2.0");
                case "tenantId" -> forged.put(field, Long.toString(OTHER));
                case "taskId" -> forged.put(field, Long.toString(task + 1));
                case "resourceVersionId" -> forged.put(
                    field,
                    Long.toString(Long.parseLong(original.path(field).asText()) + 1)
                );
                case "scope" -> forged.put(field, "TENANT");
                case "caseId" -> forged.putNull(field);
                case "knowledgeBaseId" -> forged.put(field, "123");
                case "type" -> forged.put(field, "KNOWLEDGE_INGEST");
                case "attempt" -> forged.put(field, 1);
                case "stateVersion" -> forged.put(field, 2);
                default -> fail("Unexpected field " + field);
            }
            assertFalse(
                recovery.acceptDeadLetter(
                    RabbitMqConfig.DLQ_DOCUMENT_PARSE,
                    forged,
                    3,
                    "TRANSIENT"
                ),
                field
            );
            state(task, "QUEUED", 0, 1);
            noLedger(task);
            assertEquals(1, outbox(task));
        }
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, original, 3, "TRANSIENT")
        );
        state(task, "WAITING_HUMAN", 0, 2);
    }

    @Test
    void collectorRegistersDeletedSourcesAndRecoveryStillRechecksSource() throws Exception {
        for (boolean tenantScope : List.of(false, true)) {
            var source = source(tenantScope);
            long task = parse(tenantScope ? admin : owner, source);
            var original = event(task, 0);
            db.update(
                "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
                TENANT,
                source.file()
            );
            assertTrue(
                recovery.acceptDeadLetter(
                    RabbitMqConfig.DLQ_DOCUMENT_PARSE,
                    original,
                    3,
                    "PERMANENT"
                )
            );
            state(task, "WAITING_HUMAN", 0, 2);
            assertEquals(
                "DEAD",
                db.queryForObject(
                    "SELECT delivery_status FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                    String.class,
                    TENANT,
                    task
                )
            );
            unavailable(() -> recovery.recover(admin, task, 2, request(), false));
            assertEquals(1, outbox(task));
        }
    }

    @Test
    void collectorBindsBothIndexScopesAndConservativelyMergesDeadQueueClassification()
        throws Exception {
        for (boolean tenantScope : List.of(false, true)) {
            var fixture = index(tenantScope);
            var original = event(fixture.task(), 0);
            assertEquals(tenantScope ? "TENANT" : "CASE", original.path("scope").asText());
            assertEquals(Long.toString(fixture.base()), original.path("knowledgeBaseId").asText());
            assertTrue(
                recovery.acceptDeadLetter(
                    RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST,
                    original,
                    3,
                    "TRANSIENT"
                )
            );
            state(fixture.task(), "WAITING_HUMAN", 0, 2);
            assertTrue(
                recovery.acceptDeadLetter(RabbitMqConfig.DLQ_KNOWLEDGE_INGEST, original, 3, null)
            );
            assertEquals(
                "UNCERTAIN",
                recovery.view(fixture.creator(), fixture.task()).failureKind()
            );
            assertFalse(recovery.view(fixture.creator(), fixture.task()).canRecover());
            assertTrue(
                recovery.acceptDeadLetter(
                    RabbitMqConfig.DLQ_KNOWLEDGE_INGEST,
                    original,
                    3,
                    "TRANSIENT"
                )
            );
            assertEquals(
                "UNCERTAIN",
                recovery.view(fixture.creator(), fixture.task()).failureKind()
            );
            assertEquals(1, outbox(fixture.task()));
        }
    }

    @Test
    void collectorRejectsWrongTypeQueueUnknownQueueAndNonObjectPayload() throws Exception {
        long parse = parse(owner, source(false));
        var fixture = index(false);
        var parseEvent = event(parse, 0);
        var indexEvent = event(fixture.task(), 0);
        assertFalse(
            recovery.acceptDeadLetter(
                RabbitMqConfig.QUEUE_KNOWLEDGE_INGEST,
                parseEvent,
                3,
                "TRANSIENT"
            )
        );
        assertFalse(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, indexEvent, 3, "TRANSIENT")
        );
        assertFalse(
            recovery.acceptDeadLetter(
                RabbitMqConfig.QUEUE_TASK_DELIVERY_QUARANTINE,
                parseEvent,
                3,
                "TRANSIENT"
            )
        );
        assertFalse(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, null, 3, "TRANSIENT")
        );
        assertFalse(
            recovery.acceptDeadLetter(
                RabbitMqConfig.DLQ_DOCUMENT_PARSE,
                json.getNodeFactory().textNode(parseEvent.toString()),
                3,
                "TRANSIENT"
            )
        );
        state(parse, "QUEUED", 0, 1);
        state(fixture.task(), "QUEUED", 0, 1);
        noLedger(parse);
        noLedger(fixture.task());
    }

    @Test
    void collectorAcknowledgesOldBoundOutboxButNeverChangesANewAttempt() throws Exception {
        long task = parse(owner, source(false));
        var original = event(task, 0);
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, original, 3, "TRANSIENT")
        );
        recovery.recover(owner, task, 2, request(), false);
        state(task, "QUEUED", 1, 3);
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, original, 3, "UNCERTAIN")
        );
        state(task, "QUEUED", 1, 3);
        assertEquals("RESOLVED", recovery.view(owner, task).deliveryStatus());
        var resumed = event(task, 1);
        assertEquals(3, resumed.path("stateVersion").asInt());
        var forged = resumed.deepCopy();
        forged.put("stateVersion", 2);
        assertFalse(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, forged, 3, "TRANSIENT")
        );
        state(task, "QUEUED", 1, 3);
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, resumed, 3, "TRANSIENT")
        );
        state(task, "WAITING_HUMAN", 1, 4);
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, original, 3, "UNCERTAIN")
        );
        state(task, "WAITING_HUMAN", 1, 4);
        assertEquals("TRANSIENT", recovery.view(owner, task).failureKind());
        assertEquals(2, outbox(task));
    }

    @Test
    void collectorCanRegisterARevokedCreatorWithoutAuthorizingItsRecovery() throws Exception {
        long task = parse(owner, source(false));
        var original = event(task, 0);
        revoke(OWNER, "file:read");
        assertTrue(
            recovery.acceptDeadLetter(RabbitMqConfig.DLQ_DOCUMENT_PARSE, original, 3, "TRANSIENT")
        );
        state(task, "WAITING_HUMAN", 0, 2);
        assertFalse(recovery.view(admin, task).canRecover());
        denied(() -> recovery.recover(admin, task, 2, request(), false));
        assertEquals(1, outbox(task));
    }

    @Test
    void collectorAcknowledgesTerminalBoundEventsWithoutRevivingOrCreatingLedger()
        throws Exception {
        long finished = parse(owner, source(false));
        var finishedEvent = event(finished, 0);
        completeParse(finished, 0, 1);
        assertTrue(
            recovery.acceptDeadLetter(
                RabbitMqConfig.DLQ_DOCUMENT_PARSE,
                finishedEvent,
                3,
                "TRANSIENT"
            )
        );
        state(finished, "SUCCEEDED", 0, 2);
        noLedger(finished);
        long cancelled = parse(admin, source(true));
        var cancelledEvent = event(cancelled, 0);
        tasks.requestCancel(admin, cancelled);
        assertTrue(
            recovery.acceptDeadLetter(
                RabbitMqConfig.DLQ_DOCUMENT_PARSE,
                cancelledEvent,
                3,
                "UNCERTAIN"
            )
        );
        state(cancelled, "CANCELLED", 0, 2);
        noLedger(cancelled);
    }

    @Test
    void indexEligibilityMustBeSafeBeforeEachHumanRecovery() {
        var fixture = index(false);
        dead(fixture.task(), "TRANSIENT");
        for (String safety : new String[] {
            "BUSY",
            "UNCERTAIN",
            "FAILED",
            "INPUT_CHANGED",
            "UNAVAILABLE",
            null,
            "unexpected",
        }) {
            when(
                eligibility.check(anyLong(), any(KnowledgeIndexService.IndexInput.class))
            ).thenReturn(safety);
            assertFalse(recovery.view(owner, fixture.task()).canRecover());
            conflict(() -> recovery.recover(owner, fixture.task(), 2, request(), true));
            state(fixture.task(), "WAITING_HUMAN", 0, 2);
            assertEquals(1, outbox(fixture.task()));
        }
        when(eligibility.check(anyLong(), any(KnowledgeIndexService.IndexInput.class))).thenReturn(
            "SAFE"
        );
        assertTrue(recovery.view(owner, fixture.task()).canRecover());
        recovery.recover(owner, fixture.task(), 2, request(), true);
        state(fixture.task(), "QUEUED", 1, 3);
        assertEquals(2, outbox(fixture.task()));
        verify(eligibility, atLeastOnce()).check(
            eq(fixture.task()),
            argThat(
                input ->
                    input.fileVersionId().equals(Long.toString(fixture.source().version())) &&
                    input.knowledgeBaseId().equals(Long.toString(fixture.base())) &&
                    input.chunks().getFirst().text().equals(TEXT)
            )
        );
    }

    @Test
    void humanRecoveryHttpHonorsRealSessionCsrfAndOptimisticVersion() throws Exception {
        long task = parse(owner, source(false));
        dead(task, "TRANSIENT");
        String detail = "/api/v1/tasks/" + task + "/delivery-recovery",
            resume = detail + "/recover";
        perform(get(detail)).andExpect(status().isUnauthorized());
        var session = login(owner);
        perform(get(detail).cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.taskId").value(Long.toString(task)))
            .andExpect(jsonPath("$.data.deliveryStatus").value("DEAD"))
            .andExpect(jsonPath("$.data.stateVersion").value(2))
            .andExpect(jsonPath("$.data.canRecover").value(true));
        String intent = request(),
            body = json.writeValueAsString(
                Map.of("stateVersion", 2, "requestId", intent, "confirmModelCosts", false)
            );
        perform(
            post(resume).cookie(session.cookie()).contentType("application/json").content(body)
        ).andExpect(status().isForbidden());
        perform(
            post(resume)
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "stateVersion",
                            1,
                            "requestId",
                            request(),
                            "confirmModelCosts",
                            false
                        )
                    )
                )
        ).andExpect(status().isConflict());
        perform(
            post(resume)
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("stateVersion", 2, "requestId", " ", "confirmModelCosts", false)
                    )
                )
        ).andExpect(status().isBadRequest());
        revoke(OWNER, "task:cancel");
        perform(get(detail).cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canRecover").value(false));
        perform(
            post(resume)
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isForbidden());
        restore(OWNER, "task:cancel");
        state(task, "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(task));
        for (int duplicate = 0; duplicate < 2; duplicate++) {
            perform(
                post(resume)
                    .cookie(session.cookie())
                    .header("X-CSRF-TOKEN", session.csrf())
                    .contentType("application/json")
                    .content(body)
            )
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.attempt").value(1));
        }
        state(task, "QUEUED", 1, 3);
        assertEquals(2, outbox(task));
        var otherSession = login(outsider);
        perform(get(detail).cookie(otherSession.cookie())).andExpect(status().isForbidden());
        perform(
            post(resume)
                .cookie(otherSession.cookie())
                .header("X-CSRF-TOKEN", otherSession.csrf())
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isForbidden());
        state(task, "QUEUED", 1, 3);
        assertEquals(2, outbox(task));
        verifyNoInteractions(eligibility);
    }

    @Test
    void indexRecoveryHttpRequiresExplicitCostsAndKeepsItsPublicResponseSecretFree()
        throws Exception {
        var fixture = index(false);
        dead(fixture.task(), "TRANSIENT");
        var session = login(owner);
        String path = "/api/v1/tasks/" + fixture.task() + "/delivery-recovery";
        var response = perform(get(path).cookie(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canRecover").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String cipher = db.queryForObject(
            "SELECT encrypted_key FROM ai_configuration WHERE tenant_id=? AND purpose='EMBEDDING'",
            String.class,
            TENANT
        );
        for (String secret : List.of("fake-recovery-key", cipher, "apiKey", "encryptedKey"))
            assertFalse(response.contains(secret));
        perform(
            post(path + "/recover")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "stateVersion",
                            2,
                            "requestId",
                            request(),
                            "confirmModelCosts",
                            false
                        )
                    )
                )
        ).andExpect(status().isBadRequest());
        state(fixture.task(), "WAITING_HUMAN", 0, 2);
        assertEquals(1, outbox(fixture.task()));
        var accepted = perform(
            post(path + "/recover")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("stateVersion", 2, "requestId", request(), "confirmModelCosts", true)
                    )
                )
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.type").value("KNOWLEDGE_INGEST"))
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        for (String secret : List.of("fake-recovery-key", cipher, "apiKey", "encryptedKey"))
            assertFalse(accepted.contains(secret));
        state(fixture.task(), "QUEUED", 1, 3);
        assertEquals(2, outbox(fixture.task()));
    }

    ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andDo(result -> {
            String trace = result.getResponse().getHeader("X-Trace-Id");
            if (trace != null) requestTraces.add(trace);
            for (String header : result.getResponse().getHeaders("Set-Cookie")) {
                var cookie = MockCookie.parse(header);
                if (
                    "SESSION".equals(cookie.getName()) &&
                    cookie.getMaxAge() != 0 &&
                    !cookie.getValue().isBlank()
                ) {
                    String id = new String(
                        Base64.getDecoder().decode(cookie.getValue()),
                        StandardCharsets.UTF_8
                    );
                    assertEquals(id, UUID.fromString(id).toString());
                    ownedSessionIds.add(id);
                }
            }
        });
    }

    Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        var result = perform(request).andExpect(status().isOk()).andReturn();
        String header = result.getResponse().getHeader("Set-Cookie");
        return new Session(
            header == null ? cookie : MockCookie.parse(header),
            json.readTree(result.getResponse().getContentAsString()).at("/data/token").asText()
        );
    }

    Session login(Identity actor) throws Exception {
        var initial = csrf(null);
        var result = perform(
            post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .cookie(initial.cookie())
                .header("X-CSRF-TOKEN", initial.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "recovery-only-" + actor.userId(),
                            "password",
                            PASSWORD,
                            "tenantId",
                            actor.tenantId()
                        )
                    )
                )
        )
            .andExpect(status().isOk())
            .andReturn();
        var authenticated = csrf(MockCookie.parse(result.getResponse().getHeader("Set-Cookie")));
        sessions.add(authenticated);
        return authenticated;
    }
}
