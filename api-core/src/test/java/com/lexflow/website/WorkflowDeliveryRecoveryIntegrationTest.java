package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.ContractReviewService;
import com.lexflow.service.DocumentGenerationService;
import com.lexflow.service.DocumentService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.TaskService;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.vo.ContractReport;
import com.lexflow.website.biz.TaskDeliveryRecoveryBiz;
import com.lexflow.website.core.client.WorkflowRecoveryEligibilityClient;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL and business authorization; fixtures roll back and inference is never invoked. */
@SpringBootTest(properties = "scheduling.enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class WorkflowDeliveryRecoveryIntegrationTest {

    static final long TENANT = 910000340L,
        OTHER = 910000341L,
        USER = 910000540L,
        FOREIGN = 910000541L;
    static final String CONTRACT = "验收后付款。",
        MATERIAL = "合成验收记录：十月一日完成。",
        HASH = "f".repeat(64);
    final Identity actor = new Identity(USER, TENANT, 0, "Contract fixture"),
        other = new Identity(FOREIGN, OTHER, 0, "Other fixture");

    @Autowired
    JdbcTemplate db;

    @Autowired
    ContractReviewService contracts;

    @Autowired
    CaseService cases;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskService tasks;

    @MockitoBean
    PrivateObjectStorage storage;

    @Autowired
    TaskDeliveryRecoveryBiz recovery;

    @Autowired
    DocumentGenerationService generations;

    @Autowired
    DocumentService documents;

    @MockitoBean
    WorkflowRecoveryEligibilityClient workflowProbe;

    long caseId, contractVersion;
    Source material;

    record Source(long file, long version, long parse, String text, String sha, String key) {}

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry p) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        p.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(key));
    }

    @BeforeEach
    void fixture() {
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id IN (?,?)",
                Integer.class,
                TENANT,
                OTHER
            )
        );
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'workflow-delivery-only'),(?,'workflow-delivery-other')",
            TENANT,
            OTHER
        );
        for (long user : List.of(USER, FOREIGN)) {
            long tenant = user == USER ? TENANT : OTHER;
            assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, user)
            );
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,'unused','Contract fixture')",
                user,
                "workflow-delivery-" + user
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Contract fixture')",
                user,
                tenant
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
            cases.create(actor, "CONTRACT-" + uuid(), "Synthetic contract", "CIVIL", "").id()
        );
        configs.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "synthetic-contract-key"
        );
        configs.save(
            TENANT,
            "EMBEDDING",
            "test",
            "https://models.example.test/v1",
            "embedding-test",
            "synthetic-embedding-key"
        );
        contractVersion = source(caseId, "contract.txt", CONTRACT, HASH).version();
        material = source(caseId, "acceptance.txt", MATERIAL, "a".repeat(64));
        when(workflowProbe.check(anyString(), anyLong(), any(), any())).thenReturn("SAFE");
    }

    String uuid() {
        return UUID.randomUUID().toString();
    }

    Source source(long parent, String name, String text, String hash) {
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?, ?,?,'AVAILABLE',?)",
            TENANT,
            parent,
            name,
            USER
        );
        long file = db.queryForObject(
            "SELECT MAX(id) FROM file_object WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        String key = "synthetic-contract-" + file;
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,20,'text/plain')",
            TENANT,
            file,
            key,
            hash
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,?,'SUCCEEDED',?)",
            TENANT,
            parent,
            version,
            uuid(),
            USER
        );
        long parse = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND resource_version_id=?",
            Long.class,
            TENANT,
            version
        );
        db.update(
            "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,?,?)",
            TENANT,
            parse,
            hash,
            text,
            "[{\"kind\":\"PARAGRAPH\",\"index\":1,\"startOffset\":0,\"endOffset\":" +
                text.length() +
                "}]"
        );
        return new Source(file, version, parse, text, hash, key);
    }

    ContractReport report(
        List<ContractReport.Evidence> evidence,
        List<ContractReport.BasisConflict> conflicts
    ) {
        return new ContractReport(
            HASH,
            CONTRACT,
            "服务合同",
            List.of(new ContractReport.Clause("1", "付款", CONTRACT, 0, CONTRACT.length())),
            List.of(
                new ContractReport.Risk(
                    "1",
                    "LOW",
                    "期限需核对",
                    "明确期限",
                    evidence.stream().map(ContractReport.Evidence::id).toList()
                )
            ),
            List.of("验收说明"),
            evidence,
            true,
            conflicts
        );
    }

    long waiting(Long base, ContractReport report) {
        long id = Long.parseLong(
            contracts
                .request(actor, caseId, contractVersion, "NEUTRAL", "付款", base, uuid())
                .taskId()
        );
        var input = contracts.begin(id, 0, 1);
        assertEquals(2, input.stateVersion());
        assertTrue(contracts.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report));
        return id;
    }

    long waiting() {
        return waiting(null, report(List.of(), List.of()));
    }

    int count(String table, long id) {
        return db.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND task_id=?",
            Integer.class,
            TENANT,
            id
        );
    }

    void conflict(Runnable action) {
        assertEquals(409, assertThrows(BusinessException.class, action::run).getCode());
    }

    com.fasterxml.jackson.databind.JsonNode event(long id, int sv) {
        String raw = db.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=? AND JSON_EXTRACT(payload,'$.stateVersion')=? ORDER BY created_at DESC LIMIT 1",
            String.class,
            TENANT,
            id,
            sv
        );
        try {
            var node = json.readTree(raw);
            return node.isTextual() ? json.readTree(node.textValue()) : node;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void dead(String type, long id, int sv, String kind) {
        assertTrue(
            recovery.acceptDeadLetter(
                "lexflow.q." +
                    (type.equals("CONTRACT_REVIEW") ? "contract.review" : "document.generate"),
                event(id, sv),
                3,
                kind
            )
        );
    }

    void state(long id, String status, int attempt, int sv) {
        var t = tasks.get(actor, id);
        assertEquals(status, t.status());
        assertEquals(attempt, t.attempt());
        assertEquals(
            sv,
            db.queryForObject(
                "SELECT state_version FROM async_task WHERE tenant_id=? AND id=?",
                Integer.class,
                TENANT,
                id
            )
        );
    }

    long queuedContract() {
        return Long.parseLong(
            contracts.request(actor, caseId, contractVersion, "NEUTRAL", "", null, uuid()).taskId()
        );
    }

    record GenerationFixture(long task, long doc, long version) {}

    GenerationFixture generation() {
        var doc = documents.create(actor, caseId, "固定标题", "人工基准正文");
        long id = Long.parseLong(
            generations
                .request(
                    actor,
                    caseId,
                    Long.parseLong(doc.id()),
                    Long.parseLong(doc.currentVersionId()),
                    "起草催款函",
                    uuid()
                )
                .taskId()
        );
        return new GenerationFixture(
            id,
            Long.parseLong(doc.id()),
            Long.parseLong(doc.currentVersionId())
        );
    }

    @Test
    void contractDeadRecoveryIsFeeConfirmedCasAndIntentIdempotent() {
        long id = queuedContract();
        dead("CONTRACT_REVIEW", id, 1, "TRANSIENT");
        state(id, "WAITING_HUMAN", 0, 2);
        assertEquals("DELIVERY", contracts.view(actor, id).waitingReason());
        assertTrue(recovery.view(actor, id).canRecover());
        assertNull(contracts.recoveryInput(actor, id, 0, 2).apiKey());
        conflict(() -> contracts.approve(actor, id, 2, uuid(), "不能确认"));
        conflict(() ->
            contracts.supplement(actor, id, 2, uuid(), List.of(material.parse()), "", true)
        );
        String intent = uuid();
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                recovery.recover(actor, id, 2, intent, false)
            ).getCode()
        );
        recovery.recover(actor, id, 2, intent, true);
        state(id, "QUEUED", 1, 3);
        assertNull(contracts.view(actor, id).waitingReason());
        assertEquals(2, count("outbox_event", id));
        recovery.recover(actor, id, 2, intent, true);
        assertEquals(2, count("outbox_event", id));
        conflict(() -> recovery.recover(actor, id, 3, intent, true));
        assertEquals(4, contracts.begin(id, 1, 3).stateVersion());
        assertFalse(
            contracts.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report(List.of(), List.of()))
        );
        dead("CONTRACT_REVIEW", id, 1, "UNCERTAIN");
        state(id, "RUNNING", 1, 4);
    }

    @Test
    void approvedContractReportDecisionAndOriginalSourceSurviveDeliveryRecovery() {
        long id = waiting();
        String decision = uuid();
        contracts.approve(actor, id, 3, decision, "已核对");
        var prior = contracts.view(actor, id);
        dead("CONTRACT_REVIEW", id, 4, "TRANSIENT");
        state(id, "WAITING_HUMAN", 0, 5);
        var held = contracts.view(actor, id);
        assertEquals(prior.report(), held.report());
        assertEquals(decision, held.decisionId());
        conflict(() -> contracts.approve(actor, id, 3, decision, "已核对"));
        recovery.recover(actor, id, 5, uuid(), true);
        var input = contracts.begin(id, 1, 6);
        assertEquals(7, input.stateVersion());
        assertEquals(decision, input.decisionId());
        assertEquals(Long.toString(contractVersion), input.fileVersionId());
        assertTrue(contracts.apply(TENANT, id, 1, 7, "SUCCEEDED", prior.report()));
        state(id, "SUCCEEDED", 1, 8);
        assertFalse(contracts.apply(TENANT, id, 0, 4, "SUCCEEDED", prior.report()));
    }

    @Test
    void previousRoundDeadLetterCannotInterruptNewRoundAndDuplicateCannotDowngradeUncertain() {
        long id = waiting();
        String supplement = uuid();
        contracts.supplement(actor, id, 3, supplement, List.of(material.parse()), "补充", true);
        contracts.begin(id, 0, 4);
        dead("CONTRACT_REVIEW", id, 1, "UNCERTAIN");
        state(id, "RUNNING", 0, 5);
        assertEquals(0, count("task_delivery_recovery", id));
        dead("CONTRACT_REVIEW", id, 4, "TRANSIENT");
        state(id, "WAITING_HUMAN", 0, 6);
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
            6,
            db.queryForObject(
                "SELECT registered_state_version FROM task_delivery_recovery WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                id
            )
        );
        assertEquals(1, contracts.view(actor, id).round());
        assertEquals(MATERIAL, contracts.view(actor, id).supplements().getFirst().text());
        conflict(() ->
            contracts.supplement(actor, id, 3, supplement, List.of(material.parse()), "补充", true)
        );
        dead("CONTRACT_REVIEW", id, 4, "UNCERTAIN");
        dead("CONTRACT_REVIEW", id, 4, "TRANSIENT");
        state(id, "WAITING_HUMAN", 0, 6);
        assertEquals("UNCERTAIN", contracts.view(actor, id).waitingReason());
        assertFalse(recovery.view(actor, id).canRecover());
        conflict(() -> recovery.recover(actor, id, 6, uuid(), true));
        assertEquals(2, count("outbox_event", id));
    }

    @Test
    void historicalProtocolCannotEscapeThroughSupplementOrReview() {
        long id = waiting();
        String decision = uuid();
        contracts.approve(actor, id, 3, decision, "已核对");
        db.update(
            "UPDATE contract_review SET execution_protocol=1 WHERE tenant_id=? AND task_id=?",
            TENANT,
            id
        );
        dead("CONTRACT_REVIEW", id, 4, "TRANSIENT");
        assertEquals("DELIVERY", contracts.view(actor, id).waitingReason());
        assertFalse(recovery.view(actor, id).canRecover());
        conflict(() -> recovery.recover(actor, id, 5, uuid(), true));
        conflict(() -> contracts.approve(actor, id, 3, decision, "已核对"));
        conflict(() ->
            contracts.supplement(actor, id, 5, uuid(), List.of(material.parse()), "", true)
        );
        assertEquals(2, count("outbox_event", id));
    }

    @Test
    void generationRecoveryUsesFixedVersionAndAdjacentClaimReplayAndLeavesHumanVersionCurrent() {
        var f = generation();
        dead("DOCUMENT_GENERATE", f.task(), 1, "TRANSIENT");
        state(f.task(), "WAITING_HUMAN", 0, 2);
        var fixed = generations.recoveryInput(actor, f.task(), 0, 2);
        assertNull(fixed.apiKey());
        assertEquals("人工基准正文", fixed.baseContent());
        assertEquals(Long.toString(f.version()), fixed.sourceVersionId());
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                recovery.recover(actor, f.task(), 2, uuid(), false)
            ).getCode()
        );
        recovery.recover(actor, f.task(), 2, uuid(), true);
        var input = generations.begin(f.task(), 1, 3);
        assertEquals(4, input.stateVersion());
        assertEquals(input, generations.input(f.task(), 1, 3));
        assertEquals(input, generations.begin(f.task(), 1, 3));
        assertFalse(generations.apply(TENANT, f.task(), 0, 2, "SUCCEEDED", "旧结果", List.of()));
        assertTrue(
            generations.apply(TENANT, f.task(), 1, 4, "SUCCEEDED", "待人工复核草稿", List.of())
        );
        state(f.task(), "SUCCEEDED", 1, 5);
        assertEquals(
            Long.toString(f.version()),
            documents.list(actor, caseId).getFirst().currentVersionId()
        );
        assertEquals("PENDING_REVIEW", generations.view(actor, f.task()).result().reviewStatus());
    }

    @Test
    void changedGenerationConfigUnavailableProbeAndCancelledTaskNeverRecover() {
        var f = generation();
        dead("DOCUMENT_GENERATE", f.task(), 1, "TRANSIENT");
        for (String status : List.of(
            "BUSY",
            "FAILED",
            "UNCERTAIN",
            "INPUT_CHANGED",
            "UNAVAILABLE"
        )) {
            when(workflowProbe.check(anyString(), anyLong(), any(), any())).thenReturn(status);
            assertFalse(recovery.view(actor, f.task()).canRecover());
            conflict(() -> recovery.recover(actor, f.task(), 2, uuid(), true));
            state(f.task(), "WAITING_HUMAN", 0, 2);
        }
        assertEquals(1, count("outbox_event", f.task()));
        when(workflowProbe.check(anyString(), anyLong(), any(), any())).thenReturn("SAFE");
        configs.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "changed-key"
        );
        assertFalse(recovery.view(actor, f.task()).canRecover());
        conflict(() -> recovery.recover(actor, f.task(), 2, uuid(), true));
        tasks.requestCancel(actor, f.task());
        conflict(() -> recovery.recover(actor, f.task(), 2, uuid(), true));
        assertEquals(1, count("outbox_event", f.task()));
    }

    @Test
    void forgedSourceOrMissingVersionIsQuarantinedWithoutRegistration() throws Exception {
        long id = queuedContract();
        var original = event(id, 1);
        var forged = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) forged).put(
            "resourceVersionId",
            Long.toString(material.version())
        );
        assertFalse(recovery.acceptDeadLetter("lexflow.q.contract.review", forged, 3, "TRANSIENT"));
        state(id, "QUEUED", 0, 1);
        var missing = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("stateVersion");
        assertFalse(
            recovery.acceptDeadLetter("lexflow.q.contract.review", missing, 3, "TRANSIENT")
        );
        assertEquals(0, count("task_delivery_recovery", id));
    }
}
