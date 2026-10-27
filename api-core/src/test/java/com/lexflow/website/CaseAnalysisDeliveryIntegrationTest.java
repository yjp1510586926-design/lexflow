package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.CaseAnalysisRepository;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseAnalysisService;
import com.lexflow.service.CaseService;
import com.lexflow.service.TaskResultService;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.vo.CaseAnalysisReport;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real local MySQL, transaction-owned synthetic tenants only; no HTTP, session, object, broker or model calls. */
@SpringBootTest(properties = "scheduling.enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class CaseAnalysisDeliveryIntegrationTest {

    static final long TENANT = 910000330,
        OTHER = 910000331,
        OWNER = 910000332,
        PEER = 910000333;
    static final String TEXT = "合成交付材料：验收已完成。",
        HASH = "c".repeat(64),
        KEY = "synthetic-case-delivery-key";
    final Identity admin = new Identity(TENANT, TENANT, 0, "Case delivery admin"),
        owner = new Identity(OWNER, TENANT, 0, "Case delivery owner"),
        peer = new Identity(PEER, TENANT, 0, "Case delivery peer"),
        other = new Identity(OTHER, OTHER, 0, "Other delivery tenant");

    @Autowired
    JdbcTemplate db;

    @Autowired
    CaseAnalysisService analyses;

    @Autowired
    CaseAnalysisRepository rows;

    @Autowired
    CaseService cases;

    @Autowired
    TaskService tasks;

    @Autowired
    TaskResultService taskResults;

    @Autowired
    AsyncTaskMapper taskRows;

    @Autowired
    AiConfigurationService configurations;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskDeliveryRecoveryBiz delivery;

    @Autowired
    PlatformTransactionManager transactions;

    long caseId;

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry properties) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        properties.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(bytes));
    }

    @BeforeEach
    void fixture() {
        // The commit regression owns its separately committed fixture and exact cleanup itself.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        createFixture();
    }

    private void createFixture() {
        if (
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) throw new IllegalStateException("Fixture requires its own transaction");
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
            "INSERT INTO tenant(id,name) VALUES(?,'case-delivery-only'),(?,'case-delivery-other')",
            TENANT,
            OTHER
        );
        for (long user : List.of(TENANT, OTHER, OWNER, PEER)) {
            long tenant = user == OTHER ? OTHER : TENANT;
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,'no-auth-fixture','Delivery tester')",
                user,
                "case-delivery-" + user
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Delivery role')",
                user,
                tenant,
                user == OWNER ? "LAWYER" : user == PEER ? "ASSISTANT" : "TENANT_ADMIN"
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
                .create(admin, "CASE-DELIVERY-" + uuid(), "Synthetic delivery case", "CIVIL", "")
                .id()
        );
        cases.addMember(admin, caseId, OWNER, "EDITOR");
        cases.addMember(admin, caseId, PEER, "EDITOR");
        configurations.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "case-delivery-test",
            KEY
        );
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Material belongs to the case even though the parse task result page is private to the administrator. */
    private long parsed() {
        String name = "case-delivery-" + uuid() + ".txt";
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,?,'AVAILABLE',?)",
            TENANT,
            caseId,
            name,
            TENANT
        );
        long file = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND display_name=?",
            Long.class,
            TENANT,
            name
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            TENANT,
            file,
            "synthetic-case-delivery/" + uuid(),
            HASH,
            TEXT.getBytes(StandardCharsets.UTF_8).length
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
        long parse = Long.parseLong(
            tasks.submitTask(admin, caseId, "DOCUMENT_PARSE", version, uuid()).id()
        );
        taskResults.applyResult(
            parse,
            0,
            1,
            true,
            null,
            null,
            TEXT,
            HASH,
            List.of(new TaskResultService.SourceSegment("PARAGRAPH", 1, 0, TEXT.length()))
        );
        return parse;
    }

    private long requested(long... parseIds) {
        return Long.parseLong(
            analyses
                .request(
                    owner,
                    caseId,
                    java.util.Arrays.stream(parseIds).boxed().toList(),
                    "合成交付争议背景",
                    "核对事实及证据限制",
                    uuid(),
                    true
                )
                .taskId()
        );
    }

    /** Simulates only the common Java delivery transaction's persisted task and ledger, without a Rabbit listener. */
    private int dead(long id, String kind) {
        db.update(
            "UPDATE async_task SET status='WAITING_HUMAN',state_version=state_version+1 WHERE tenant_id=? AND id=?",
            TENANT,
            id
        );
        var task = taskRows.selectByIdForUpdate(TENANT, id);
        analyses.markDeliveryWaiting(task, kind);
        db.update(
            """
            INSERT INTO task_delivery_recovery(tenant_id,task_id,attempt,registered_state_version,delivery_status,retry_count,failure_kind)
            VALUES(?,?,?,?,'DEAD',3,?)
            """,
            TENANT,
            id,
            task.getAttempt(),
            task.getStateVersion(),
            kind
        );
        return task.getStateVersion();
    }

    private void conflict(org.junit.jupiter.api.function.Executable action) {
        assertEquals(409, assertThrows(BusinessException.class, action).getCode());
    }

    private CaseAnalysisReport report(CaseAnalysisService.Input input) {
        var source = input.sources().getFirst();
        var citation = new CaseAnalysisReport.Citation(
            "C1",
            source.parseTaskId(),
            source.fileVersionId(),
            source.sha256(),
            "PARAGRAPH",
            1,
            0,
            TEXT.length(),
            TEXT
        );
        return new CaseAnalysisReport(
            List.of(new CaseAnalysisReport.Fact("材料记载完成验收", List.of("C1"))),
            List.of(),
            List.of(
                new CaseAnalysisReport.Issue(
                    "验收证明",
                    "HIGH",
                    "仅能确认材料记载",
                    "核对原件及相对方确认",
                    List.of("C1")
                )
            ),
            List.of(),
            List.of(citation),
            "仅依据合成材料，不能认定实际验收或诉讼结果",
            false,
            true
        );
    }

    @Test
    void newProtocolAndExactDeadInputRemainReadOnlyAndCredentialFree() throws Exception {
        long first = parsed(),
            second = parsed(),
            id = requested(first, second);
        assertEquals(2, rows.get(TENANT, id).executionProtocol());
        assertThrows(AccessDenied.class, () -> tasks.get(owner, first));
        int version = dead(id, "TRANSIENT");
        int outbox = db.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            Integer.class,
            TENANT,
            id
        );
        var input = analyses.recoveryInput(owner, id, 0, version);
        assertEquals(2, input.sources().size());
        assertEquals(Long.toString(first), input.sources().getFirst().parseTaskId());
        assertEquals(version, input.stateVersion());
        assertEquals(0, input.attempt());
        assertNull(input.decisionId());
        assertEquals("WAITING_HUMAN", tasks.get(owner, id).status());
        assertEquals("DELIVERY", rows.get(TENANT, id).waitingReason());
        assertEquals(
            outbox,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        String body = json.writeValueAsString(input);
        assertFalse(body.contains("apiKey"));
        assertFalse(body.contains("encryptedKey"));
        assertFalse(body.contains(KEY));
        assertFalse(input.toString().contains(TEXT));
        assertFalse(input.toString().contains(KEY));
    }

    @Test
    void missingStaleResolvedWrongReasonAndLegacyDeliveryCannotBecomeRecoveryInput() {
        long id = requested(parsed());
        int version = dead(id, "TRANSIENT");
        conflict(() -> analyses.recoveryInput(owner, id, 1, version));
        conflict(() -> analyses.recoveryInput(owner, id, 0, version - 1));
        db.update(
            "UPDATE task_delivery_recovery SET registered_state_version=? WHERE tenant_id=? AND task_id=?",
            version - 1,
            TENANT,
            id
        );
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
        db.update(
            "UPDATE task_delivery_recovery SET registered_state_version=?,delivery_status='RESOLVED' WHERE tenant_id=? AND task_id=?",
            version,
            TENANT,
            id
        );
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
        db.update(
            "UPDATE task_delivery_recovery SET delivery_status='DEAD' WHERE tenant_id=? AND task_id=?",
            TENANT,
            id
        );
        db.update(
            "UPDATE case_analysis SET waiting_reason='REVIEW' WHERE tenant_id=? AND task_id=?",
            TENANT,
            id
        );
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
        db.update(
            "UPDATE case_analysis SET waiting_reason='DELIVERY',execution_protocol=1 WHERE tenant_id=? AND task_id=?",
            TENANT,
            id
        );
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
        conflict(() -> analyses.currentRecoveryInput(owner, id));
        db.update(
            "UPDATE case_analysis SET execution_protocol=2 WHERE tenant_id=? AND task_id=?",
            TENANT,
            id
        );
        db.update("DELETE FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?", TENANT, id);
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
    }

    @Test
    void recoveryRechecksPrivateTaskActorCreatorAllMaterialsAndModelRevision() {
        long id = requested(parsed(), parsed());
        int version = dead(id, "TRANSIENT");
        var input = analyses.recoveryInput(owner, id, 0, version);
        assertThrows(AccessDenied.class, () -> analyses.recoveryInput(peer, id, 0, version));
        assertThrows(AccessDenied.class, () -> analyses.recoveryInput(other, id, 0, version));
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='task:cancel'",
            TENANT,
            OWNER
        );
        assertThrows(AccessDenied.class, () -> analyses.recoveryInput(owner, id, 0, version));
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'task:cancel')",
            TENANT,
            OWNER
        );
        // An administrator cannot use its own privilege to execute after the original creator loses update authority.
        db.update(
            "UPDATE case_member SET member_role='READER' WHERE tenant_id=? AND case_id=? AND user_id=?",
            TENANT,
            caseId,
            OWNER
        );
        assertThrows(AccessDenied.class, () -> analyses.recoveryInput(admin, id, 0, version));
        db.update(
            "UPDATE case_member SET member_role='EDITOR' WHERE tenant_id=? AND case_id=? AND user_id=?",
            TENANT,
            caseId,
            OWNER
        );
        long secondFile = Long.parseLong(input.sources().get(1).fileId());
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            secondFile
        );
        assertThrows(AccessDenied.class, () -> analyses.recoveryInput(owner, id, 0, version));
        db.update(
            "UPDATE file_object SET status='AVAILABLE' WHERE tenant_id=? AND id=?",
            TENANT,
            secondFile
        );
        configurations.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "case-delivery-test",
            "rotated-synthetic-key"
        );
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
        conflict(() -> analyses.currentRecoveryInput(owner, id));
    }

    @Test
    void changedSnapshotOrTaskSourceIdentityRejectsRecovery() {
        long id = requested(parsed(), parsed());
        int version = dead(id, "TRANSIENT");
        var input = analyses.recoveryInput(owner, id, 0, version);
        long second = Long.parseLong(input.sources().get(1).parseTaskId());
        db.update(
            "UPDATE task_parse_result SET content_text=? WHERE tenant_id=? AND task_id=?",
            "更".repeat(TEXT.length()),
            TENANT,
            second
        );
        assertThrows(AccessDenied.class, () -> analyses.recoveryInput(owner, id, 0, version));
        db.update(
            "UPDATE task_parse_result SET content_text=? WHERE tenant_id=? AND task_id=?",
            TEXT,
            TENANT,
            second
        );
        db.update(
            "UPDATE async_task SET resource_version_id=? WHERE tenant_id=? AND id=?",
            input.sources().get(1).fileVersionId(),
            TENANT,
            id
        );
        conflict(() -> analyses.recoveryInput(owner, id, 0, version));
    }

    @Test
    void deliveryBlocksOldDecisionReplayAndDirectRecoverWhileKeepingTheApprovedReport() {
        long id = requested(parsed());
        var execution = analyses.begin(id, 0, 1);
        var report = report(execution);
        assertTrue(analyses.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report));
        String decision = uuid();
        analyses.approve(owner, id, 3, decision, "已核对材料及建议边界");
        var before = rows.get(TENANT, id);
        int version = dead(id, "TRANSIENT");
        assertEquals(before.reportJson(), rows.get(TENANT, id).reportJson());
        assertEquals(before.decisionId(), rows.get(TENANT, id).decisionId());
        conflict(() -> analyses.approve(owner, id, 3, decision, "已核对材料及建议边界"));
        conflict(() -> analyses.recover(owner, id, version));
        conflict(() -> analyses.input(id, 0, version));
        assertFalse(analyses.apply(TENANT, id, 0, 4, "SUCCEEDED", report));
        var input = analyses.recoveryInput(owner, id, 0, version);
        assertEquals(decision, input.decisionId());
        // The common recovery owns attempt/sv/ledger/outbox. This fixture exercises only the domain's post-CAS behavior.
        db.update(
            "UPDATE async_task SET status='QUEUED',attempt=attempt+1,state_version=state_version+1 WHERE tenant_id=? AND id=?",
            TENANT,
            id
        );
        analyses.clearDeliveryWaiting(taskRows.selectByIdForUpdate(TENANT, id));
        db.update(
            "UPDATE task_delivery_recovery SET delivery_status='RESOLVED' WHERE tenant_id=? AND task_id=?",
            TENANT,
            id
        );
        var after = rows.get(TENANT, id);
        assertNull(after.waitingReason());
        assertEquals(before.reportJson(), after.reportJson());
        assertEquals(before.decisionId(), after.decisionId());
        assertEquals(before.sourcesJson(), after.sourcesJson());
        var resumed = analyses.begin(id, 1, version + 1);
        assertEquals(version + 2, resumed.stateVersion());
        assertEquals(decision, resumed.decisionId());
        assertEquals(execution.sources(), resumed.sources());
        assertFalse(analyses.apply(TENANT, id, 0, version + 2, "SUCCEEDED", report));
        assertTrue(analyses.apply(TENANT, id, 1, version + 2, "SUCCEEDED", report));
        assertEquals("SUCCEEDED", tasks.get(owner, id).status());
    }

    @Test
    void uncertainDeliveryPreservesEvidenceAndCannotBeClearedOrBypassed() {
        long id = requested(parsed());
        var execution = analyses.begin(id, 0, 1);
        var report = report(execution);
        assertTrue(analyses.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report));
        String decision = uuid();
        analyses.approve(owner, id, 3, decision, "核对完成");
        var before = rows.get(TENANT, id);
        int version = dead(id, "UNCERTAIN");
        assertEquals("UNCERTAIN", rows.get(TENANT, id).waitingReason());
        assertEquals(before.reportJson(), rows.get(TENANT, id).reportJson());
        assertEquals(decision, analyses.recoveryInput(owner, id, 0, version).decisionId());
        conflict(() -> analyses.approve(owner, id, 3, decision, "核对完成"));
        conflict(() -> analyses.recover(owner, id, version));
        conflict(() -> analyses.clearDeliveryWaiting(taskRows.selectByIdForUpdate(TENANT, id)));
        assertEquals("CANCELLED", tasks.requestCancel(owner, id).status());
        assertFalse(analyses.apply(TENANT, id, 0, version, "SUCCEEDED", report));
    }

    @Test
    void approvedSameVersionEventRegistersDeadLetterAndOnlyItsDuplicateMayEscalateIt()
        throws Exception {
        long id = requested(parsed());
        var execution = analyses.begin(id, 0, 1);
        var report = report(execution);
        assertTrue(analyses.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report));
        String decision = uuid();
        analyses.approve(owner, id, 3, decision, "核对后继续");
        String raw = db.queryForObject(
            """
            SELECT CAST(payload AS CHAR) FROM outbox_event
            WHERE tenant_id=? AND task_id=? AND JSON_EXTRACT(payload,'$.stateVersion')=4
            """,
            String.class,
            TENANT,
            id
        );
        var event = json.readTree(raw);
        if (event.isTextual()) event = json.readTree(event.textValue());
        assertEquals(4, event.path("stateVersion").intValue());
        assertEquals("RUNNING", tasks.get(owner, id).status());
        assertTrue(delivery.acceptDeadLetter("lexflow.q.case.analysis", event, 3, "TRANSIENT"));
        assertEquals("WAITING_HUMAN", tasks.get(owner, id).status());
        assertEquals(
            5,
            db.queryForObject(
                "SELECT state_version FROM async_task WHERE tenant_id=? AND id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        assertEquals(
            4,
            db.queryForObject(
                "SELECT source_state_version FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        assertEquals(
            5,
            db.queryForObject(
                "SELECT registered_state_version FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        assertEquals("DELIVERY", rows.get(TENANT, id).waitingReason());
        assertEquals(decision, rows.get(TENANT, id).decisionId());
        assertEquals(report, analyses.view(owner, id).report());
        String initial = db.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=? AND JSON_EXTRACT(payload,'$.stateVersion')=1",
            String.class,
            TENANT,
            id
        );
        var old = json.readTree(initial);
        if (old.isTextual()) old = json.readTree(old.textValue());
        assertTrue(delivery.acceptDeadLetter("lexflow.q.case.analysis", old, 3, "UNCERTAIN"));
        assertEquals(
            "TRANSIENT",
            db.queryForObject(
                "SELECT failure_kind FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                String.class,
                TENANT,
                id
            )
        );
        assertTrue(delivery.acceptDeadLetter("lexflow.q.case.analysis", event, 3, "UNCERTAIN"));
        assertTrue(delivery.acceptDeadLetter("lexflow.q.case.analysis", event, 3, "TRANSIENT"));
        assertEquals("UNCERTAIN", rows.get(TENANT, id).waitingReason());
        assertEquals(
            "UNCERTAIN",
            db.queryForObject(
                "SELECT failure_kind FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                String.class,
                TENANT,
                id
            )
        );
        assertEquals(
            5,
            db.queryForObject(
                "SELECT state_version FROM async_task WHERE tenant_id=? AND id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        conflict(() -> analyses.approve(owner, id, 3, decision, "核对后继续"));
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deniedRecoveryViewCommitsItsReadTransactionWithoutUnexpectedRollback() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var transaction = new TransactionTemplate(transactions);
        boolean owned = false;
        try {
            transaction.executeWithoutResult(status -> createFixture());
            owned = true;
            long id = transaction.execute(status -> {
                long task = requested(parsed());
                dead(task, "TRANSIENT");
                return task;
            });
            // currentRecoveryInput succeeds here, then recoveryInput refuses the wrong wait reason.
            transaction.executeWithoutResult(status ->
                db.update(
                    "UPDATE case_analysis SET waiting_reason='REVIEW' WHERE tenant_id=? AND task_id=?",
                    TENANT,
                    id
                )
            );
            assertNotRecoverableAfterRealCommit(id);
            transaction.executeWithoutResult(status ->
                db.update(
                    "UPDATE case_analysis SET waiting_reason='DELIVERY',execution_protocol=1 WHERE tenant_id=? AND task_id=?",
                    TENANT,
                    id
                )
            );
            assertNotRecoverableAfterRealCommit(id);
            transaction.executeWithoutResult(status -> {
                db.update(
                    "UPDATE case_analysis SET execution_protocol=2 WHERE tenant_id=? AND task_id=?",
                    TENANT,
                    id
                );
                db.update(
                    "UPDATE case_member SET member_role='READER' WHERE tenant_id=? AND case_id=? AND user_id=?",
                    TENANT,
                    caseId,
                    OWNER
                );
            });
            assertNotRecoverableAfterRealCommit(id);
            transaction.executeWithoutResult(status -> {
                db.update(
                    "UPDATE case_member SET member_role='EDITOR' WHERE tenant_id=? AND case_id=? AND user_id=?",
                    TENANT,
                    caseId,
                    OWNER
                );
                db.update(
                    "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND case_id=?",
                    TENANT,
                    caseId
                );
            });
            assertNotRecoverableAfterRealCommit(id);
            transaction.executeWithoutResult(status -> {
                db.update(
                    "UPDATE file_object SET status='AVAILABLE' WHERE tenant_id=? AND case_id=?",
                    TENANT,
                    caseId
                );
                configurations.save(
                    TENANT,
                    "LLM",
                    "test",
                    "https://models.example.test/v1",
                    "case-delivery-test",
                    "commit-regression-key"
                );
            });
            assertNotRecoverableAfterRealCommit(id);
        } finally {
            if (owned) transaction.executeWithoutResult(status -> clearCommittedFixture());
        }
    }

    private void assertNotRecoverableAfterRealCommit(long id) {
        // No surrounding test/TransactionTemplate transaction masks the service proxy's actual commit.
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var view = assertDoesNotThrow(() -> delivery.view(owner, id));
        assertFalse(view.canRecover());
        assertNotNull(view.recoveryMessage());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("WAITING_HUMAN", view.status());
        assertEquals(0, view.attempt());
        assertEquals(2, view.stateVersion());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_delivery_recovery_request WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
    }

    private void clearCommittedFixture() {
        // Only tenants proven absent before this test were committed. Respect FK order, never disable FK checks.
        for (String table : List.of(
            "task_delivery_recovery_request",
            "task_delivery_recovery",
            "case_analysis",
            "outbox_event",
            "task_parse_result",
            "async_task",
            "file_version",
            "file_object",
            "case_member",
            "case_info",
            "audit_log",
            "role_permission",
            "user_role",
            "sys_role",
            "ai_configuration",
            "tenant_user"
        )) {
            db.update("DELETE FROM " + table + " WHERE tenant_id IN (?,?)", TENANT, OTHER);
            assertEquals(
                0,
                db.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE tenant_id IN (?,?)",
                    Integer.class,
                    TENANT,
                    OTHER
                )
            );
        }
        db.update("DELETE FROM tenant WHERE id IN (?,?)", TENANT, OTHER);
        db.update("DELETE FROM sys_user WHERE id IN (?,?,?,?)", TENANT, OTHER, OWNER, PEER);
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
    }
}
