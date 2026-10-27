package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseAnalysisService;
import com.lexflow.service.CaseService;
import com.lexflow.service.TaskResultService;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.vo.CaseAnalysisReport;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL/Redis; synthetic source metadata and credentials; no model or file downloads. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class CaseAnalysisIntegrationTest {

    static final long TENANT = 910000275,
        OTHER = 910000276,
        OWNER = 910000277,
        PEER = 910000278;
    static final String TEXT = "合成案件资料：已交付货物。",
        HASH = "e".repeat(64),
        PASSWORD = "Analysis-only-password!";
    static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    final Identity admin = new Identity(TENANT, TENANT, 0, "Analysis admin"),
        owner = new Identity(OWNER, TENANT, 0, "Analysis owner"),
        peer = new Identity(PEER, TENANT, 0, "Analysis peer"),
        other = new Identity(OTHER, OTHER, 0, "Other");

    @Autowired
    JdbcTemplate db;

    @Autowired
    CaseAnalysisService analyses;

    @Autowired
    CaseService cases;

    @Autowired
    TaskService tasks;

    @Autowired
    TaskResultService taskResults;

    @Autowired
    AiConfigurationService configurations;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    org.springframework.session.SessionRepository<?> sessions;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    long caseId;
    Cookie cookie;
    String csrf, address;
    final Set<String> sessionIds = new LinkedHashSet<>(),
        traces = new LinkedHashSet<>();

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry p) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        p.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(bytes));
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
            "INSERT INTO tenant(id,name) VALUES(?,'case-analysis-only'),(?,'case-analysis-other')",
            TENANT,
            OTHER
        );
        for (long user : List.of(TENANT, OTHER, OWNER, PEER)) {
            long tenant = user == OTHER ? OTHER : TENANT;
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,?,'Analysis tester')",
                user,
                "analysis-only-" + user,
                PASSWORD_HASH
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Analysis role')",
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
            cases.create(admin, "ANALYSIS-" + uuid(), "Synthetic analysis", "CIVIL", "").id()
        );
        cases.addMember(admin, caseId, OWNER, "EDITOR");
        configurations.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "case-analysis-test",
            "synthetic-analysis-key"
        );
        address = "fd00::" + uuid().substring(0, 8);
    }

    @AfterEach
    void cleanupHttp() throws Exception {
        try {
            if (cookie != null && csrf != null) perform(
                post("/api/v1/auth/logout").cookie(cookie).header("X-CSRF-TOKEN", csrf)
            ).andExpect(status().isOk());
        } finally {
            for (String id : sessionIds) {
                sessions.deleteById(id);
                assertNull(sessions.findById(id));
            }
            redis.delete("lexflow:login:ip:" + address);
        }
    }

    @AfterTransaction
    void cleanupAudits() {
        for (String trace : traces)
            db.update(
                "DELETE FROM audit_log WHERE object_type='http_request' AND trace_id=? AND (tenant_id IS NULL OR tenant_id IN (?,?))",
                trace,
                TENANT,
                OTHER
            );
    }

    String uuid() {
        return UUID.randomUUID().toString();
    }

    ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(request);
        var response = result.andReturn().getResponse();
        String trace = response.getHeader("X-Trace-Id");
        if (trace != null) traces.add(trace);
        for (String header : response.getHeaders("Set-Cookie")) {
            var c = MockCookie.parse(header);
            if ("SESSION".equals(c.getName())) try {
                sessionIds.add(
                    new String(Base64.getDecoder().decode(c.getValue()), StandardCharsets.UTF_8)
                );
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    void login() throws Exception {
        var first = perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        cookie = MockCookie.parse(first.getResponse().getHeader("Set-Cookie"));
        csrf = json.readTree(first.getResponse().getContentAsString()).at("/data/token").asText();
        var result = perform(
            post("/api/v1/auth/login")
                .cookie(cookie)
                .header("X-CSRF-TOKEN", csrf)
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "analysis-only-" + OWNER,
                            "password",
                            PASSWORD,
                            "tenantId",
                            TENANT
                        )
                    )
                )
        )
            .andExpect(status().isOk())
            .andReturn();
        cookie = MockCookie.parse(result.getResponse().getHeader("Set-Cookie"));
        csrf = json
            .readTree(
                perform(get("/api/v1/auth/csrf").cookie(cookie))
                    .andReturn()
                    .getResponse()
                    .getContentAsString()
            )
            .at("/data/token")
            .asText();
    }

    long parsed(long caseId, boolean located) {
        String name = "analysis-" + uuid() + ".txt";
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,?,'AVAILABLE',?)",
            TENANT,
            caseId,
            name,
            OWNER
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
            "synthetic-analysis/" + uuid(),
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
            tasks.submitTask(owner, caseId, "DOCUMENT_PARSE", version, uuid()).id()
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
            located
                ? List.of(new TaskResultService.SourceSegment("PARAGRAPH", 1, 0, TEXT.length()))
                : null
        );
        return parse;
    }

    long requested(List<Long> ids) {
        return Long.parseLong(
            analyses
                .request(
                    owner,
                    caseId,
                    ids,
                    "合成货物争议背景",
                    "分析交付事实与待核实事项",
                    uuid(),
                    true
                )
                .taskId()
        );
    }

    CaseAnalysisReport report(CaseAnalysisService.Input input, boolean high) {
        var s = input.sources().getFirst();
        var c = new CaseAnalysisReport.Citation(
            "C1",
            s.parseTaskId(),
            s.fileVersionId(),
            s.sha256(),
            "PARAGRAPH",
            1,
            0,
            TEXT.length(),
            TEXT
        );
        return new CaseAnalysisReport(
            List.of(new CaseAnalysisReport.Fact("材料记载已交货", List.of("C1"))),
            List.of(),
            List.of(
                new CaseAnalysisReport.Issue(
                    "交货证明",
                    high ? "HIGH" : "LOW",
                    "仅能确认材料记载",
                    "核对原始收货凭证",
                    List.of("C1")
                )
            ),
            List.of(),
            List.of(c),
            "仅依据所选合成材料，不能认定真实交付或诉讼结果",
            false,
            high
        );
    }

    @Test
    void immutableMultiSourceSubmissionAndIdempotencyProduceOneOutbox() {
        long a = parsed(caseId, true),
            b = parsed(caseId, true);
        String key = uuid();
        var first = analyses.request(owner, caseId, List.of(b, a), "背景", "要求", key, true);
        var again = analyses.request(owner, caseId, List.of(a, b), "背景", "要求", key, true);
        assertEquals(first, again);
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                first.taskId()
            )
        );
        var input = analyses.begin(Long.parseLong(first.taskId()), 0, 1);
        assertEquals(2, input.stateVersion());
        assertEquals(2, input.sources().size());
        assertEquals(Long.toString(a), input.sources().getFirst().parseTaskId());
        assertEquals(
            409,
            assertThrows(BusinessException.class, () ->
                analyses.request(owner, caseId, List.of(a), "背景", "要求", key, true)
            ).getCode()
        );
    }

    @Test
    void costsBoundsEmptyDuplicateAndUnlocatedMaterialsAreRejected() {
        long parse = parsed(caseId, true);
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                analyses.request(owner, caseId, List.of(parse), "背景", "要求", uuid(), false)
            ).getCode()
        );
        for (var ids : List.of(
            List.<Long>of(),
            List.of(parse, parse),
            Collections.nCopies(11, parse)
        ))
            assertEquals(
                400,
                assertThrows(BusinessException.class, () -> requested(ids)).getCode()
            );
        long legacy = parsed(caseId, false);
        assertEquals(
            400,
            assertThrows(BusinessException.class, () -> requested(List.of(legacy))).getCode()
        );
        assertEquals(1, analyses.sources(owner, caseId).size());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM case_analysis WHERE tenant_id=?",
                Integer.class,
                TENANT
            )
        );
    }

    @Test
    void eligibleSharedCaseMaterialDoesNotExposeAnotherCreatorsPrivateTaskPage() {
        long parse = parsed(caseId, true);
        db.update(
            "UPDATE async_task SET created_by=? WHERE tenant_id=? AND id=?",
            TENANT,
            TENANT,
            parse
        );
        assertThrows(AccessDenied.class, () -> tasks.get(owner, parse));
        var selected = analyses.sources(owner, caseId);
        assertEquals(1, selected.size());
        assertEquals(Long.toString(parse), selected.getFirst().parseTaskId());
        long task = requested(List.of(parse));
        assertEquals(
            Long.toString(parse),
            analyses.begin(task, 0, 1).sources().getFirst().parseTaskId()
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            selected.getFirst().fileId()
        );
        assertTrue(analyses.sources(owner, caseId).isEmpty());
        assertThrows(AccessDenied.class, () -> analyses.input(task, 0, 2));
    }

    @Test
    void everySelectedSourceIsReauthorizedDuringExecutionAndOnReportView() {
        long first = parsed(caseId, true),
            second = parsed(caseId, true),
            task = requested(List.of(first, second));
        var input = analyses.begin(task, 0, 1);
        long file = Long.parseLong(input.sources().get(1).fileId());
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            file
        );
        assertThrows(AccessDenied.class, () -> analyses.input(task, 0, 2));
        assertThrows(AccessDenied.class, () -> analyses.view(owner, task));
        assertThrows(AccessDenied.class, () ->
            analyses.apply(TENANT, task, 0, 2, "SUCCEEDED", report(input, false))
        );
        assertEquals("RUNNING", tasks.get(owner, task).status());
    }

    @Test
    void changedParseTextOrShaCannotReplaceFixedInputs() {
        long parse = parsed(caseId, true),
            task = requested(List.of(parse));
        analyses.begin(task, 0, 1);
        db.update(
            "UPDATE task_parse_result SET content_text=? WHERE tenant_id=? AND task_id=?",
            TEXT + "变更",
            TENANT,
            parse
        );
        assertThrows(AccessDenied.class, () -> analyses.input(task, 0, 2));
    }

    @Test
    void otherTenantReaderAndOtherTaskOwnerCannotReadOrSubmit() {
        long parse = parsed(caseId, true),
            task = requested(List.of(parse));
        assertThrows(AccessDenied.class, () -> analyses.view(other, task));
        db.update("UPDATE sys_role SET code='ASSISTANT' WHERE tenant_id=? AND id=?", TENANT, PEER);
        cases.addMember(admin, caseId, PEER, "READER");
        assertThrows(AccessDenied.class, () -> analyses.view(peer, task));
        assertThrows(AccessDenied.class, () ->
            analyses.request(peer, caseId, List.of(parse), "背景", "要求", uuid(), true)
        );
    }

    @Test
    void originalCreatorLosesPermissionEvenIfAdministratorCanStillReadCase() {
        long task = requested(List.of(parsed(caseId, true)));
        analyses.begin(task, 0, 1);
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='file:read'",
            TENANT,
            OWNER
        );
        assertThrows(AccessDenied.class, () -> analyses.input(task, 0, 2));
        assertThrows(AccessDenied.class, () -> analyses.view(admin, task));
    }

    @Test
    void creatorReaderDowngradeAndArchivePreventFurtherExecution() {
        long task = requested(List.of(parsed(caseId, true)));
        analyses.begin(task, 0, 1);
        db.update(
            "UPDATE case_member SET member_role='READER' WHERE tenant_id=? AND case_id=? AND user_id=?",
            TENANT,
            caseId,
            OWNER
        );
        assertThrows(AccessDenied.class, () -> analyses.input(task, 0, 2));
        db.update(
            "UPDATE case_member SET member_role='EDITOR' WHERE tenant_id=? AND case_id=? AND user_id=?",
            TENANT,
            caseId,
            OWNER
        );
        cases.update(admin, caseId, 0, "Synthetic analysis", "", "ACTIVE");
        cases.update(admin, caseId, 1, "Synthetic analysis", "", "ARCHIVED");
        assertThrows(AccessDenied.class, () -> analyses.input(task, 0, 2));
    }

    @Test
    void lowRiskValidReportCompletesAndLateDuplicateIsIgnored() {
        long task = requested(List.of(parsed(caseId, true)));
        var input = analyses.begin(task, 0, 1);
        assertTrue(analyses.apply(TENANT, task, 0, 2, "SUCCEEDED", report(input, false)));
        assertEquals("SUCCEEDED", analyses.view(owner, task).status());
        assertFalse(analyses.apply(TENANT, task, 0, 2, "FAILED", null));
        assertNotNull(analyses.view(owner, task).report());
    }

    @Test
    void highRiskRequiresHumanDecisionAndDecisionReplayDoesNotRepeatOutbox() {
        long task = requested(List.of(parsed(caseId, true)));
        var input = analyses.begin(task, 0, 1);
        var high = report(input, true);
        assertEquals(
            409,
            assertThrows(BusinessException.class, () ->
                analyses.apply(TENANT, task, 0, 2, "SUCCEEDED", high)
            ).getCode()
        );
        assertTrue(analyses.apply(TENANT, task, 0, 2, "WAITING_HUMAN", high));
        assertEquals("REVIEW", analyses.view(owner, task).waitingReason());
        assertEquals(
            409,
            assertThrows(BusinessException.class, () ->
                analyses.approve(owner, task, 2, uuid(), "已核对")
            ).getCode()
        );
        String decision = uuid();
        var first = analyses.approve(owner, task, 3, decision, "已核对证据缺口和原件");
        var repeat = analyses.approve(owner, task, 3, decision, "已核对证据缺口和原件");
        assertEquals(first, repeat);
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                TENANT,
                task
            )
        );
        assertTrue(analyses.apply(TENANT, task, 0, 4, "SUCCEEDED", high));
        assertEquals("SUCCEEDED", analyses.view(owner, task).status());
    }

    @Test
    void forgedQuotesWrongPageHashOrUnselectedSourceNeverPublish() {
        long task = requested(List.of(parsed(caseId, true)));
        var input = analyses.begin(task, 0, 1);
        var valid = report(input, false);
        var c = valid.citations().getFirst();
        var tampered = List.of(
            new CaseAnalysisReport.Citation(
                "C1",
                c.parseTaskId(),
                c.fileVersionId(),
                c.sha256(),
                "PARAGRAPH",
                2,
                0,
                TEXT.length(),
                TEXT
            ),
            new CaseAnalysisReport.Citation(
                "C1",
                "9999",
                c.fileVersionId(),
                c.sha256(),
                "PARAGRAPH",
                1,
                0,
                TEXT.length(),
                TEXT
            ),
            new CaseAnalysisReport.Citation(
                "C1",
                c.parseTaskId(),
                c.fileVersionId(),
                "f".repeat(64),
                "PARAGRAPH",
                1,
                0,
                TEXT.length(),
                TEXT
            ),
            new CaseAnalysisReport.Citation(
                "C1",
                c.parseTaskId(),
                c.fileVersionId(),
                c.sha256(),
                "PARAGRAPH",
                1,
                0,
                TEXT.length(),
                "x".repeat(TEXT.length())
            )
        );
        for (var bad : tampered) {
            var report = new CaseAnalysisReport(
                valid.facts(),
                valid.pendingVerification(),
                valid.issues(),
                valid.missingMaterials(),
                List.of(bad),
                valid.recommendationBoundary(),
                false,
                false
            );
            assertEquals(
                400,
                assertThrows(BusinessException.class, () ->
                    analyses.apply(TENANT, task, 0, 2, "SUCCEEDED", report)
                ).getCode()
            );
        }
        assertEquals("RUNNING", tasks.get(owner, task).status());
    }

    @Test
    void materialInsufficiencyCannotSkipHumanReview() {
        long task = requested(List.of(parsed(caseId, true)));
        var input = analyses.begin(task, 0, 1);
        var v = report(input, false);
        var invalid = new CaseAnalysisReport(
            v.facts(),
            List.of("尚未核实收货"),
            v.issues(),
            List.of(),
            v.citations(),
            v.recommendationBoundary(),
            true,
            false
        );
        assertThrows(IllegalArgumentException.class, () ->
            analyses.apply(TENANT, task, 0, 2, "SUCCEEDED", invalid)
        );
    }

    @Test
    void uncertainExecutionCannotApproveRecoverOrPublishAndMayBeCancelled() {
        long task = requested(List.of(parsed(caseId, true)));
        analyses.begin(task, 0, 1);
        assertTrue(analyses.apply(TENANT, task, 0, 2, "UNCERTAIN", null));
        assertEquals(
            409,
            assertThrows(BusinessException.class, () ->
                analyses.approve(owner, task, 3, uuid(), "同意")
            ).getCode()
        );
        assertEquals(
            409,
            assertThrows(BusinessException.class, () -> analyses.recover(owner, task, 3)).getCode()
        );
        assertEquals("CANCELLED", tasks.requestCancel(owner, task).status());
        assertFalse(analyses.apply(TENANT, task, 0, 3, "SUCCEEDED", null));
    }

    @Test
    void staleAttemptCancellationAndGenericResultCannotOverwriteAnalysis() {
        long task = requested(List.of(parsed(caseId, true)));
        var input = analyses.begin(task, 0, 1);
        assertFalse(analyses.apply(TENANT, task, 1, 2, "SUCCEEDED", report(input, false)));
        assertThrows(IllegalArgumentException.class, () ->
            taskResults.applyResult(task, 0, 2, false, null, "bypass")
        );
        assertEquals("CANCELLED", tasks.requestCancel(owner, task).status());
        assertFalse(analyses.apply(TENANT, task, 0, 2, "SUCCEEDED", report(input, false)));
    }

    @Test
    void modelRevisionChangeRejectsInputAndRecoveryWithoutNewCalls() {
        long task = requested(List.of(parsed(caseId, true)));
        analyses.begin(task, 0, 1);
        configurations.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "case-analysis-test",
            "changed-synthetic-key"
        );
        assertEquals(
            409,
            assertThrows(BusinessException.class, () -> analyses.input(task, 0, 2)).getCode()
        );
        assertEquals(
            409,
            assertThrows(BusinessException.class, () -> analyses.recover(owner, task, 2)).getCode()
        );
    }

    @Test
    void httpSubmissionHas202CsrfCostConfirmationAndNoCredentialLeak() throws Exception {
        long parse = parsed(caseId, true);
        login();
        var input = new LinkedHashMap<String, Object>(
            Map.of(
                "caseId",
                caseId,
                "parseTaskIds",
                List.of(parse),
                "background",
                "合成背景",
                "requirements",
                "分析争议",
                "idempotencyKey",
                uuid(),
                "confirmModelCosts",
                false
            )
        );
        perform(
            post("/api/v1/case-analyses")
                .cookie(cookie)
                .header("X-CSRF-TOKEN", csrf)
                .contentType("application/json")
                .content(json.writeValueAsString(input))
        ).andExpect(status().isBadRequest());
        input.put("confirmModelCosts", true);
        var result = perform(
            post("/api/v1/case-analyses")
                .cookie(cookie)
                .header("X-CSRF-TOKEN", csrf)
                .contentType("application/json")
                .content(json.writeValueAsString(input))
        )
            .andExpect(status().isAccepted())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
        String id = json
            .readTree(result.getResponse().getContentAsString())
            .at("/data/taskId")
            .asText();
        var view = perform(get("/api/v1/case-analyses/" + id).cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertFalse(view.contains("apiKey"));
        assertFalse(view.contains("synthetic-analysis-key"));
        perform(
            post("/api/v1/case-analyses")
                .cookie(cookie)
                .contentType("application/json")
                .content(json.writeValueAsString(input))
        ).andExpect(status().isForbidden());
    }
}
