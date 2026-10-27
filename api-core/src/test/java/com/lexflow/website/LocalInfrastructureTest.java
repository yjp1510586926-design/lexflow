package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in against this project's disposable local MySQL/Redis/MinIO only. Fixtures roll back. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class LocalInfrastructureTest {

    @Autowired
    JdbcTemplate db;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate testRedis;

    @Autowired
    org.springframework.session.SessionRepository<?> testSessions;

    private final java.util.Set<String> ownedSessionIds = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> ownedRequestTraces = new java.util.LinkedHashSet<>();
    private String testAddress;

    @Autowired
    com.lexflow.service.CaseService cases;

    @Autowired
    com.lexflow.service.OrganizationService organizations;

    @Autowired
    com.lexflow.service.DocumentGenerationService generations;

    @Autowired
    com.lexflow.service.FileService fileService;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.lexflow.service.file.PrivateObjectStorage storage;

    @Autowired
    com.lexflow.service.FileCleanupService fileCleanup;

    @Autowired
    com.lexflow.service.UploadMetadataService uploadMetadata;

    private final long tenant = 900000001L,
        user = 900000001L,
        other = 900000002L;

    @BeforeEach
    void fixtures() {
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        testAddress =
            "fd00::" +
            nonce.substring(0, 4) +
            ":" +
            nonce.substring(4, 8) +
            ":" +
            nonce.substring(8, 12) +
            ":" +
            nonce.substring(12, 16);
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?, 'integration-only'),(?,'other-test-tenant')",
            tenant,
            other
        );
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'integration-only',?,'Test User')",
            user,
            new BCryptPasswordEncoder(12).encode("Test-only-password!")
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Test Admin')",
            tenant,
            tenant
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            tenant,
            user,
            tenant
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            tenant,
            tenant
        );
    }

    @AfterEach
    void clearOwnedHttpState() {
        if (testAddress != null) testRedis.delete("lexflow:login:ip:" + testAddress);
        for (String id : ownedSessionIds) {
            testSessions.deleteById(id);
            assertNull(testSessions.findById(id));
        }
    }

    @org.springframework.test.context.transaction.AfterTransaction
    void clearOwnedIndependentAudits() {
        for (String trace : ownedRequestTraces)
            db.update(
                "DELETE FROM audit_log WHERE object_type='http_request' AND trace_id=? AND (tenant_id IS NULL OR tenant_id IN (?,?))",
                trace,
                tenant,
                other
            );
    }

    private org.springframework.test.web.servlet.ResultActions perform(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) throws Exception {
        return mvc.perform(request).andDo(result -> {
            String trace = result.getResponse().getHeader("X-Trace-Id");
            if (trace != null) ownedRequestTraces.add(trace);
            for (String header : result.getResponse().getHeaders("Set-Cookie")) {
                var cookie = MockCookie.parse(header);
                if (
                    "SESSION".equals(cookie.getName()) &&
                    cookie.getMaxAge() != 0 &&
                    !cookie.getValue().isBlank()
                ) {
                    String id = new String(
                        java.util.Base64.getDecoder().decode(cookie.getValue()),
                        java.nio.charset.StandardCharsets.UTF_8
                    );
                    assertEquals(id, java.util.UUID.fromString(id).toString());
                    ownedSessionIds.add(id);
                }
            }
        });
    }

    private record Session(Cookie value, String token) {}

    private Session csrf(Cookie session) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (session != null) request.cookie(session);
        var result = perform(request).andExpect(status().isOk()).andReturn();
        return new Session(
            result.getResponse().getHeader("Set-Cookie") == null
                ? session
                : MockCookie.parse(result.getResponse().getHeader("Set-Cookie")),
            json.readTree(result.getResponse().getContentAsString()).at("/data/token").asText()
        );
    }

    private Session login() throws Exception {
        var s = csrf(null);
        var result = perform(
            post("/api/v1/auth/login")
                .with(r -> {
                    r.setRemoteAddr(testAddress);
                    return r;
                })
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(
                    "{\"username\":\"integration-only\",\"password\":\"Test-only-password!\",\"tenantId\":" +
                        tenant +
                        "}"
                )
        )
            .andExpect(status().isOk())
            .andReturn();
        return csrf(MockCookie.parse(result.getResponse().getHeader("Set-Cookie")));
    }

    @org.springframework.test.context.DynamicPropertySource
    static void testAiMasterKey(org.springframework.test.context.DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String encoded = java.util.Base64.getEncoder().encodeToString(key);
        registry.add("LEXFLOW_AI_MASTER_KEY", () -> encoded);
    }

    @Test
    void aiConfigurationPersistsEncryptedAndIsTenantScoped() throws Exception {
        var s = login();
        String endpoint = "/api/v1/settings/ai/LLM";
        String body =
            "{\"provider\":\"test\",\"baseUrl\":\"https://example.com/v1\",\"model\":\"test-model\",\"apiKey\":\"integration-fake-key\"}";
        perform(
            put(endpoint)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isOk());
        String encrypted = db.queryForObject(
            "SELECT encrypted_key FROM ai_configuration WHERE tenant_id=? AND purpose='LLM'",
            String.class,
            tenant
        );
        assertTrue(encrypted.startsWith("v1:"));
        assertFalse(encrypted.contains("integration-fake-key"));
        db.update(
            "INSERT INTO ai_configuration(tenant_id,purpose,provider,base_url,model_name,encrypted_key) VALUES(?,'LLM','other','https://example.com','other-model','other-cipher')",
            other
        );
        var response = perform(get("/api/v1/settings/ai").cookie(s.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.storageReady").value(true))
            .andExpect(jsonPath("$.data.items[0].model").value("test-model"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertFalse(response.contains(encrypted));
        assertFalse(response.contains("integration-fake-key"));
        assertFalse(response.contains("other-model"));
        perform(delete(endpoint).cookie(s.value()).header("X-CSRF-TOKEN", s.token())).andExpect(
            status().isOk()
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration WHERE tenant_id=?",
                Integer.class,
                other
            )
        );
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action IN ('ai:config:save','ai:config:delete')",
                Integer.class,
                tenant
            )
        );
        db.update("DELETE FROM user_role WHERE tenant_id=? AND user_id=?", tenant, user);
        perform(
            put(endpoint)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isForbidden());
    }

    @Autowired
    com.lexflow.service.TaskResultService taskResults;

    @Autowired
    com.lexflow.service.TaskService taskService;

    @Autowired
    com.lexflow.service.KnowledgeService knowledgeService;

    @Autowired
    com.lexflow.service.KnowledgeIndexService knowledgeIndexes;

    @Autowired
    com.lexflow.service.AiConfigurationService aiConfigurations;

    @Autowired
    com.lexflow.service.AiConfigurationTestService aiConnectionTests;

    @Autowired
    com.lexflow.dao.KnowledgeRepository knowledgeRepository;

    @Autowired
    com.lexflow.website.biz.KnowledgeSearchBiz knowledgeSearch;

    @Autowired
    com.lexflow.service.ContractReviewService contractReviews;

    @Autowired
    com.lexflow.service.DocumentService documents;

    @Autowired
    com.lexflow.service.DocumentExportService documentExports;

    @Autowired
    com.lexflow.dao.DocumentExportRepository exportJobs;

    @Autowired
    com.lexflow.website.biz.DocumentExportBiz exportBiz;

    // Existing fixtures read a fresh snapshot explicitly before each ordinary document mutation.
    private com.lexflow.service.DocumentService.DocumentView currentDocument(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long id
    ) {
        return documents
            .list(actor, caseId)
            .stream()
            .filter(d -> d.id().equals(Long.toString(id)))
            .findFirst()
            .orElseThrow();
    }

    private com.lexflow.service.DocumentService.VersionView saveDocument(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long id,
        String content
    ) {
        var current = currentDocument(actor, caseId, id);
        return documents.addVersion(
            actor,
            caseId,
            id,
            content,
            current.version(),
            Long.parseLong(current.currentVersionId())
        );
    }

    private com.lexflow.service.DocumentService.DocumentView publishDocument(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long id
    ) {
        var current = currentDocument(actor, caseId, id);
        return documents.publish(
            actor,
            caseId,
            id,
            current.version(),
            Long.parseLong(current.currentVersionId())
        );
    }

    private com.lexflow.service.DocumentService.VersionView restoreDocument(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long id,
        long from
    ) {
        var current = currentDocument(actor, caseId, id);
        return documents.restoreVersion(
            actor,
            caseId,
            id,
            from,
            current.version(),
            Long.parseLong(current.currentVersionId())
        );
    }

    private com.lexflow.service.identity.Identity aiTestActor() {
        return new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
    }

    private String saveAiTestConfiguration(String purpose, String key) {
        aiConfigurations.save(
            tenant,
            purpose,
            "synthetic",
            "https://example.com/v1",
            "synthetic-model",
            key
        );
        return db.queryForObject(
            "SELECT revision FROM ai_configuration WHERE tenant_id=? AND purpose=?",
            String.class,
            tenant,
            purpose
        );
    }

    @Test
    void aiConnectionTestBindsSavedRevisionAndIdempotencyWithoutExposingSecrets() throws Exception {
        var actor = aiTestActor();
        String secret = "test-only-connection-secret";
        String revision = saveAiTestConfiguration("LLM", secret),
            request = java.util.UUID.randomUUID().toString();
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration_test WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        var prepared = aiConnectionTests.prepare(actor, "LLM", revision, request);
        long id = Long.parseLong(prepared.id());
        assertEquals(
            prepared.id(),
            aiConnectionTests.prepare(actor, "LLM", revision, request).id()
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration_test WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        var claim = aiConnectionTests.begin(actor, id);
        assertNotNull(claim);
        assertEquals(secret, claim.model().apiKey());
        assertFalse(claim.toString().contains(secret));
        assertFalse(claim.model().toString().contains(secret));
        assertNull(aiConnectionTests.begin(actor, id));
        aiConnectionTests.finish(
            claim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 7, null, null)
        );
        aiConnectionTests.finish(
            claim,
            new com.lexflow.service.AiConfigurationTestService.Outcome(
                "FAILED",
                8,
                null,
                "PROBE_FAILED"
            )
        );
        assertEquals("SUCCEEDED", aiConnectionTests.get(actor, id).status());
        var session = login();
        String endpoint = "/api/v1/settings/ai/LLM/tests";
        String body = json.writeValueAsString(
            java.util.Map.of("revision", revision, "requestId", request)
        );
        // Repeating the public POST reuses the finished row, so it never reaches a model service.
        String response = perform(
            post(endpoint)
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(body)
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.id").value(prepared.id()))
            .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String latest = perform(
            get(endpoint + "/latest")
                .param("revision", revision)
                .cookie(session.value())
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(prepared.id()))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String persisted = db
            .queryForMap(
                "SELECT * FROM ai_configuration_test WHERE tenant_id=? AND id=?",
                tenant,
                id
            )
            .toString();
        String cipher = db.queryForObject(
            "SELECT encrypted_key FROM ai_configuration WHERE tenant_id=? AND purpose='LLM'",
            String.class,
            tenant
        );
        for (String text : java.util.List.of(
            response,
            latest,
            persisted,
            json.writeValueAsString(prepared)
        )) {
            assertFalse(text.contains(secret));
            assertFalse(text.contains(cipher));
            assertFalse(text.contains("apiKey"));
            assertFalse(text.contains("encryptedKey"));
        }
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='ai:config:test:result'",
                Integer.class,
                tenant,
                prepared.id()
            )
        );
    }

    @Test
    void aiConnectionTestKeyReplacementAndDeletionInvalidatePendingAndSuccessfulResults() {
        var actor = aiTestActor();
        String first = saveAiTestConfiguration("LLM", "test-only-first-key");
        var old = aiConnectionTests.prepare(
            actor,
            "LLM",
            first,
            java.util.UUID.randomUUID().toString()
        );
        var claim = aiConnectionTests.begin(actor, Long.parseLong(old.id()));
        String next = saveAiTestConfiguration("LLM", "test-only-second-key");
        assertNotEquals(first, next);
        aiConnectionTests.finish(
            claim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 4, null, null)
        );
        var stale = aiConnectionTests.get(actor, claim.id());
        assertEquals("STALE", stale.status());
        assertNull(stale.elapsedMs());
        assertEquals("STALE", aiConnectionTests.latest(actor, "LLM", first).status());
        assertNull(aiConnectionTests.latest(actor, "LLM", next));
        var outdated = assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            aiConnectionTests.prepare(actor, "LLM", first, java.util.UUID.randomUUID().toString())
        );
        assertEquals(409, outdated.getCode());
        var current = aiConnectionTests.prepare(
            actor,
            "LLM",
            next,
            java.util.UUID.randomUUID().toString()
        );
        var currentClaim = aiConnectionTests.begin(actor, Long.parseLong(current.id()));
        assertEquals("test-only-second-key", currentClaim.model().apiKey());
        aiConnectionTests.finish(
            currentClaim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 5, null, null)
        );
        assertEquals("SUCCEEDED", aiConnectionTests.get(actor, currentClaim.id()).status());
        assertEquals(1, aiConfigurations.delete(tenant, "LLM"));
        assertEquals("STALE", aiConnectionTests.get(actor, currentClaim.id()).status());
        assertNull(aiConnectionTests.begin(actor, currentClaim.id()));
        String embedding = saveAiTestConfiguration("EMBEDDING", "test-only-embedding-key");
        var pending = aiConnectionTests.prepare(
            actor,
            "EMBEDDING",
            embedding,
            java.util.UUID.randomUUID().toString()
        );
        var pendingClaim = aiConnectionTests.begin(actor, Long.parseLong(pending.id()));
        aiConfigurations.delete(tenant, "EMBEDDING");
        aiConnectionTests.finish(
            pendingClaim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 6, 8, null)
        );
        assertEquals("STALE", aiConnectionTests.get(actor, pendingClaim.id()).status());
        assertNull(aiConnectionTests.get(actor, pendingClaim.id()).dimension());
    }

    @Test
    void aiConnectionTestRechecksTenantAdminAndSessionBeforeDisclosingOrAcceptingResult() {
        var actor = aiTestActor();
        String revision = saveAiTestConfiguration("LLM", "test-only-admin-key");
        var prepared = aiConnectionTests.prepare(
            actor,
            "LLM",
            revision,
            java.util.UUID.randomUUID().toString()
        );
        var claim = aiConnectionTests.begin(actor, Long.parseLong(prepared.id()));
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", other, user);
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Other Test Admin')",
            other,
            other
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            other,
            user,
            other
        );
        var outsider = new com.lexflow.service.identity.Identity(
            user,
            other,
            0,
            "Other tenant admin"
        );
        assertEquals(
            404,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                aiConnectionTests.get(outsider, claim.id())
            ).getCode()
        );
        assertNull(aiConnectionTests.latest(outsider, "LLM", revision));
        db.update("DELETE FROM user_role WHERE tenant_id=? AND user_id=?", tenant, user);
        aiConnectionTests.finish(
            claim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 7, null, null)
        );
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM ai_configuration_test WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                claim.id()
            )
        );
        assertEquals(
            "ACCESS_REVOKED",
            db.queryForObject(
                "SELECT error_code FROM ai_configuration_test WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                claim.id()
            )
        );
        assertEquals(
            403,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                aiConnectionTests.get(actor, claim.id())
            ).getCode()
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            tenant,
            user,
            tenant
        );
        var second = aiConnectionTests.prepare(
            actor,
            "LLM",
            revision,
            java.util.UUID.randomUUID().toString()
        );
        var secondClaim = aiConnectionTests.begin(actor, Long.parseLong(second.id()));
        db.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?", user);
        aiConnectionTests.finish(
            secondClaim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 8, null, null)
        );
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM ai_configuration_test WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                secondClaim.id()
            )
        );
        assertEquals(
            403,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                aiConnectionTests.latest(actor, "LLM", revision)
            ).getCode()
        );
    }

    @Test
    void aiConnectionTestExpiredReservationIsUnknownAndNeverClaimedAgain() {
        var actor = aiTestActor();
        String revision = saveAiTestConfiguration("LLM", "test-only-expired-key");
        String request = java.util.UUID.randomUUID().toString();
        var prepared = aiConnectionTests.prepare(actor, "LLM", revision, request);
        long id = Long.parseLong(prepared.id());
        db.update(
            "UPDATE ai_configuration_test SET created_at=DATE_SUB(NOW(6),INTERVAL 3 MINUTE) WHERE tenant_id=? AND id=?",
            tenant,
            id
        );
        assertNull(aiConnectionTests.begin(actor, id));
        assertEquals("UNKNOWN", aiConnectionTests.get(actor, id).status());
        assertEquals(
            "UNKNOWN",
            aiConnectionTests.prepare(actor, "LLM", revision, request).status()
        );
        assertNull(aiConnectionTests.begin(actor, id));
        String nextRequest = java.util.UUID.randomUUID().toString();
        var processing = aiConnectionTests.prepare(actor, "LLM", revision, nextRequest);
        var claim = aiConnectionTests.begin(actor, Long.parseLong(processing.id()));
        db.update(
            "UPDATE ai_configuration_test SET started_at=DATE_SUB(NOW(6),INTERVAL 3 MINUTE) WHERE tenant_id=? AND id=?",
            tenant,
            claim.id()
        );
        assertEquals("UNKNOWN", aiConnectionTests.get(actor, claim.id()).status());
        assertNull(aiConnectionTests.begin(actor, claim.id()));
        aiConnectionTests.finish(
            claim,
            new com.lexflow.service.AiConfigurationTestService.Outcome("SUCCEEDED", 9, null, null)
        );
        assertEquals("UNKNOWN", aiConnectionTests.get(actor, claim.id()).status());
        assertEquals(
            processing.id(),
            aiConnectionTests.prepare(actor, "LLM", revision, nextRequest).id()
        );
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration_test WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
    }

    @Test
    void aiConnectionTestBoundsNewPaidIntentsAndRejectsReusedRequestForDifferentPurpose() {
        var actor = aiTestActor();
        String revision = saveAiTestConfiguration("LLM", "test-only-limited-key");
        String embedding = saveAiTestConfiguration("EMBEDDING", "test-only-vector-key"),
            request = java.util.UUID.randomUUID().toString();
        var prepared = aiConnectionTests.prepare(actor, "LLM", revision, request);
        assertEquals(
            409,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                aiConnectionTests.prepare(
                    actor,
                    "LLM",
                    revision,
                    java.util.UUID.randomUUID().toString()
                )
            ).getCode()
        );
        assertEquals(
            409,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                aiConnectionTests.prepare(actor, "EMBEDDING", embedding, request)
            ).getCode()
        );
        var claim = aiConnectionTests.begin(actor, Long.parseLong(prepared.id()));
        aiConnectionTests.finish(
            claim,
            new com.lexflow.service.AiConfigurationTestService.Outcome(
                "FAILED",
                1,
                null,
                "PROBE_FAILED"
            )
        );
        for (int i = 0; i < 2; i++) {
            var next = aiConnectionTests.prepare(
                actor,
                "LLM",
                revision,
                java.util.UUID.randomUUID().toString()
            );
            var nextClaim = aiConnectionTests.begin(actor, Long.parseLong(next.id()));
            aiConnectionTests.finish(
                nextClaim,
                new com.lexflow.service.AiConfigurationTestService.Outcome(
                    "FAILED",
                    1,
                    null,
                    "PROBE_FAILED"
                )
            );
        }
        assertEquals(
            429,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                aiConnectionTests.prepare(
                    actor,
                    "LLM",
                    revision,
                    java.util.UUID.randomUUID().toString()
                )
            ).getCode()
        );
        assertEquals(
            3,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration_test WHERE tenant_id=? AND purpose='LLM'",
                Integer.class,
                tenant
            )
        );
    }

    private record ExportFixture(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long document,
        long version,
        long task,
        String text
    ) {}

    private ExportFixture exportFixture() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(
            cases
                .create(
                    actor,
                    "ASYNC-EXPORT-" + java.util.UUID.randomUUID(),
                    "Export task case",
                    "CIVIL",
                    ""
                )
                .id()
        );
        String text = "甲方：张三\n乙方：李四\n已保存正文💼";
        var d = documents.create(actor, c, "租赁协议💼", text);
        long id = Long.parseLong(
            documentExports
                .request(
                    actor,
                    c,
                    Long.parseLong(d.id()),
                    Long.parseLong(d.currentVersionId()),
                    java.util.UUID.randomUUID().toString()
                )
                .taskId()
        );
        return new ExportFixture(
            actor,
            c,
            Long.parseLong(d.id()),
            Long.parseLong(d.currentVersionId()),
            id,
            text
        );
    }

    private void storeExport(com.lexflow.service.DocumentExportService.Claim claim)
        throws Exception {
        var path = java.nio.file.Files.createTempFile("lexflow-export-test-", ".txt");
        try {
            java.nio.file.Files.write(path, claim.bytes());
            storage.put(claim.objectKey(), path, "text/plain;charset=UTF-8");
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
    }

    @AfterEach
    void clearOwnedExportObjects() throws Exception {
        for (String key : db.queryForList(
            "SELECT object_key FROM document_export_artifact WHERE tenant_id=?",
            String.class,
            tenant
        )) {
            assertTrue(key.startsWith("tenants/" + tenant + "/exports/"));
            org.mockito.Mockito.doCallRealMethod().when(storage).remove(key);
            storage.remove(key);
        }
    }

    @Test
    void asynchronousExportSubmissionBindsVersionAndIsIdempotentWithoutAiKey() throws Exception {
        var f = exportFixture();
        var session = login();
        String key = java.util.UUID.randomUUID().toString();
        String endpoint = "/api/v1/cases/" + f.caseId() + "/documents/" + f.document() + "/exports";
        String body = json.writeValueAsString(
            java.util.Map.of("expectedVersionId", Long.toString(f.version()), "idempotencyKey", key)
        );
        String response = perform(
            post(endpoint)
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(body)
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        long id = json.readTree(response).at("/data/taskId").asLong();
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_configuration WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        var next = saveDocument(f.actor(), f.caseId(), f.document(), "新的保存正文");
        assertEquals(
            Long.toString(id),
            documentExports.request(f.actor(), f.caseId(), f.document(), f.version(), key).taskId()
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            documentExports.request(
                f.actor(),
                f.caseId(),
                f.document(),
                Long.parseLong(next.id()),
                key
            )
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            documentExports.request(
                f.actor(),
                f.caseId(),
                f.document(),
                f.version(),
                java.util.UUID.randomUUID().toString()
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                id
            )
        );
        String event = db.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            String.class,
            tenant,
            id
        );
        assertFalse(event.contains(f.text()));
        assertFalse(response.contains("objectKey"));
        var envelope = json.readTree(event);
        assertEquals(Long.toString(f.version()), envelope.path("resourceVersionId").asText());
        assertEquals(Long.toString(f.caseId()), envelope.path("caseId").asText());
        assertDoesNotThrow(() -> java.util.UUID.fromString(envelope.path("traceId").asText()));
        assertThrows(IllegalArgumentException.class, () ->
            taskResults.applyResult(id, 0, 1, true, "forged-artifact", null)
        );
        assertEquals("QUEUED", documentExports.view(f.actor(), id).status());
    }

    @Test
    void asynchronousExportProducesPrivateFixedVersionAndDuplicateDeliveryDoesNotRegenerate()
        throws Exception {
        var f = exportFixture();
        saveDocument(f.actor(), f.caseId(), f.document(), "第二版正文");
        exportBiz.process(tenant, f.task(), 0, 1);
        exportBiz.process(tenant, f.task(), 0, 1);
        var ready = documentExports.view(f.actor(), f.task());
        assertEquals("SUCCEEDED", ready.status());
        assertEquals(3, ready.stateVersion());
        assertEquals(1, ready.versionNo());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM document_export_artifact WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                f.task()
            )
        );
        var session = login();
        var response = perform(
            get("/api/v1/tasks/" + f.task() + "/export/download")
                .cookie(session.value())
                .accept("text/plain, application/json")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("X-Document-Version", "1"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentType("text/plain;charset=UTF-8"))
            .andReturn()
            .getResponse();
        assertArrayEquals(
            f.text().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            response.getContentAsByteArray()
        );
        assertTrue(response.getHeader("Content-Disposition").contains("filename*=UTF-8''"));
        perform(get("/api/v1/tasks/" + f.task() + "/export").cookie(session.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.versionNo").value(1));
        var metadata = documentExports.download(f.actor(), f.task());
        var job = exportJobs.get(tenant, f.task());
        assertFalse(
            documentExports.reserveCleanup(
                new com.lexflow.dao.DocumentExportRepository.Artifact(
                    job.token(),
                    tenant,
                    f.task(),
                    metadata.key()
                )
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:export:ready' AND object_id=?",
                Integer.class,
                tenant,
                Long.toString(f.task())
            )
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            documentExports.view(
                new com.lexflow.service.identity.Identity(user, other, 0, "Other"),
                f.task()
            )
        );
    }

    @Test
    void asynchronousExportCancellationFencesQueuedAndUploadedWorkAndCleansLateObjects()
        throws Exception {
        var queued = exportFixture();
        assertEquals(
            "CANCELLED",
            taskService.requestCancel(queued.actor(), queued.task()).status()
        );
        exportBiz.process(tenant, queued.task(), 0, 1);
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM document_export_artifact WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                queued.task()
            )
        );
        var f = exportFixture();
        var claim = documentExports.begin(tenant, f.task(), 0, 1);
        assertNotNull(claim);
        storeExport(claim);
        assertEquals("CANCELLED", taskService.requestCancel(f.actor(), f.task()).status());
        assertFalse(documentExports.complete(claim));
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            documentExports.download(f.actor(), f.task())
        );
        assertNull(exportJobs.get(tenant, f.task()).objectKey());
        var artifact = new com.lexflow.dao.DocumentExportRepository.Artifact(
            claim.token(),
            tenant,
            f.task(),
            claim.objectKey()
        );
        db.update(
            "UPDATE document_export_artifact SET next_cleanup_at=NOW(6) WHERE token=?",
            claim.token()
        );
        assertTrue(documentExports.reserveCleanup(artifact));
        storage.remove(artifact.objectKey());
        exportJobs.cleaned(artifact, null);
        assertThrows(Exception.class, () -> storage.open(artifact.objectKey()));
        // A storage request can finish after the first deletion. The tombstone remains eligible for a later pass.
        storeExport(claim);
        db.update(
            "UPDATE document_export_artifact SET next_cleanup_at=NOW(6) WHERE token=?",
            claim.token()
        );
        assertTrue(
            exportJobs
                .cleanupCandidates()
                .stream()
                .anyMatch(a -> a.token().equals(claim.token()))
        );
        assertTrue(documentExports.reserveCleanup(artifact));
        storage.remove(artifact.objectKey());
        exportJobs.cleaned(artifact, null);
        assertEquals(
            2,
            db.queryForObject(
                "SELECT cleanup_attempts FROM document_export_artifact WHERE token=?",
                Integer.class,
                claim.token()
            )
        );
        assertThrows(Exception.class, () -> storage.open(artifact.objectKey()));
        assertFalse(documentExports.complete(claim));
    }

    @Test
    void asynchronousExportLeaseRecoveryFencesOldWorkerAndRetriesAreBounded() {
        var f = exportFixture();
        var old = documentExports.begin(tenant, f.task(), 0, 1);
        assertNotNull(old);
        assertNull(documentExports.begin(tenant, f.task(), 0, 1));
        db.update(
            "UPDATE document_export_job SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE tenant_id=? AND task_id=?",
            tenant,
            f.task()
        );
        documentExports.recoverExpired(tenant, f.task());
        documentExports.recoverExpired(tenant, f.task());
        assertFalse(documentExports.complete(old));
        var state = documentExports.view(f.actor(), f.task());
        assertEquals(1, state.attempt());
        assertEquals(3, state.stateVersion());
        int count = db.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            Integer.class,
            tenant,
            f.task()
        );
        assertEquals(2, count);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var current = documentExports.view(f.actor(), f.task());
            var claim = documentExports.begin(tenant, f.task(), attempt, current.stateVersion());
            assertNotNull(claim);
            documentExports.fail(claim, true);
            assertFalse(documentExports.complete(claim));
        }
        state = documentExports.view(f.actor(), f.task());
        assertEquals("FAILED", state.status());
        assertEquals(3, state.attempt());
        assertEquals(9, state.stateVersion());
        assertEquals(
            4,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                f.task()
            )
        );
    }

    @Test
    void asynchronousExportUncertainUploadSchedulesRetryWithoutPublishingOrImmediateReexecution()
        throws Exception {
        var f = exportFixture();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) throw new java.io.IOException(
                "synthetic lost upload response"
            );
            return null;
        })
            .when(storage)
            .put(
                org.mockito.ArgumentMatchers.startsWith("tenants/" + tenant + "/exports/"),
                org.mockito.ArgumentMatchers.any(java.nio.file.Path.class),
                org.mockito.ArgumentMatchers.anyString()
            );
        exportBiz.process(tenant, f.task(), 0, 1);
        var retry = documentExports.view(f.actor(), f.task());
        assertEquals("QUEUED", retry.status());
        assertEquals(1, retry.attempt());
        assertEquals(3, retry.stateVersion());
        assertNull(exportJobs.get(tenant, f.task()).objectKey());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=? AND next_retry_at>NOW(6)",
                Integer.class,
                tenant,
                f.task()
            )
        );
        exportBiz.process(tenant, f.task(), 0, 1);
        assertEquals(1, calls.get());
        exportBiz.process(tenant, f.task(), retry.attempt(), retry.stateVersion());
        assertEquals(2, calls.get());
        assertEquals("SUCCEEDED", documentExports.view(f.actor(), f.task()).status());
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM document_export_artifact WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                f.task()
            )
        );
        assertArrayEquals(
            f.text().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            exportBiz.download(f.actor(), f.task())
        );
        db.update(
            "UPDATE document_export_artifact SET next_cleanup_at=NOW(6) WHERE tenant_id=? AND task_id=?",
            tenant,
            f.task()
        );
        assertEquals(
            1,
            exportJobs
                .cleanupCandidates()
                .stream()
                .filter(a -> a.task() == f.task())
                .count()
        );
    }

    @Test
    void asynchronousExportRechecksPermissionsBeforePublicationAndAfterReadingFile()
        throws Exception {
        var f = exportFixture();
        var claim = documentExports.begin(tenant, f.task(), 0, 1);
        storeExport(claim);
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='document:read'",
            tenant,
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            documentExports.complete(claim)
        );
        documentExports.fail(claim, false);
        assertNull(exportJobs.get(tenant, f.task()).objectKey());
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                f.task()
            )
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'document:read')",
            tenant,
            tenant
        );
        var ready = exportFixture();
        exportBiz.process(tenant, ready.task(), 0, 1);
        var metadata = documentExports.download(ready.actor(), ready.task());
        org.mockito.Mockito.doAnswer(invocation -> {
            db.update(
                "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='document:read'",
                tenant,
                tenant
            );
            return invocation.callRealMethod();
        })
            .when(storage)
            .open(metadata.key());
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            exportBiz.download(ready.actor(), ready.task())
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:export:download'",
                Integer.class,
                tenant
            )
        );
    }

    @Test
    void asynchronousExportRejectsUnreviewedAndTamperedSourcesAndCorruptArtifacts()
        throws Exception {
        var f = exportFixture();
        db.update(
            "UPDATE document_version SET source='AI_DRAFT',review_status='PENDING_REVIEW' WHERE tenant_id=? AND id=?",
            tenant,
            f.version()
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            documentExports.request(
                f.actor(),
                f.caseId(),
                f.document(),
                f.version(),
                java.util.UUID.randomUUID().toString()
            )
        );
        exportBiz.process(tenant, f.task(), 0, 1);
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                f.task()
            )
        );
        var changed = exportFixture();
        db.update(
            "UPDATE document_version SET content_text='changed after submission' WHERE tenant_id=? AND id=?",
            tenant,
            changed.version()
        );
        exportBiz.process(tenant, changed.task(), 0, 1);
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                changed.task()
            )
        );
        var corrupt = exportFixture();
        exportBiz.process(tenant, corrupt.task(), 0, 1);
        var metadata = documentExports.download(corrupt.actor(), corrupt.task());
        var path = java.nio.file.Files.createTempFile("lexflow-corrupt-export-", ".txt");
        try {
            java.nio.file.Files.writeString(path, "corrupt artifact");
            storage.put(metadata.key(), path, "text/plain");
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            exportBiz.download(corrupt.actor(), corrupt.task())
        );
    }

    @Test
    void documentExportPreservesChineseBytesUsesSafeFilenameAndChecksDisplayedVersion()
        throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(cases.create(actor, "EXPORT", "Export case", "CIVIL", "").id());
        String title = "中文协议/测试\r\n标题💼";
        String text = "甲方：张三\n乙方：李四\n金额：￥1000。💼";
        var d = documents.create(actor, c, title, text);
        var session = login();
        String path = "/api/v1/cases/" + c + "/documents/" + d.id() + "/export";
        var response = perform(
            get(path)
                .accept("text/plain, application/json")
                .cookie(session.value())
                .param("expectedVersionId", d.currentVersionId())
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Document-Version", "1"))
            .andExpect(header().string("X-Document-Status", "DRAFT"))
            .andExpect(content().contentType("text/plain;charset=UTF-8"))
            .andReturn()
            .getResponse();
        assertArrayEquals(
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            response.getContentAsByteArray()
        );
        String disposition = response.getHeader("Content-Disposition");
        assertTrue(
            disposition.startsWith("attachment; filename=\"document_v1.txt\"; filename*=UTF-8''")
        );
        assertFalse(disposition.contains("\r"));
        assertFalse(disposition.contains("\n"));
        String filename = java.net.URLDecoder.decode(
            disposition.substring(disposition.indexOf("UTF-8''") + 7),
            java.nio.charset.StandardCharsets.UTF_8
        );
        assertEquals("中文协议_测试__标题💼_v1.txt", filename);
        var next = saveDocument(actor, c, Long.parseLong(d.id()), "第二版中文正文");
        perform(
            get(path)
                .accept("text/plain, application/json")
                .cookie(session.value())
                .param("expectedVersionId", d.currentVersionId())
        ).andExpect(status().isConflict());
        perform(
            get(path)
                .accept("text/plain, application/json")
                .cookie(session.value())
                .param("expectedVersionId", next.id())
        )
            .andExpect(status().isOk())
            .andExpect(content().string("第二版中文正文"));
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:export'",
                Integer.class,
                tenant
            )
        );
        String audit = db.queryForObject(
            "SELECT detail_json FROM audit_log WHERE tenant_id=? AND action='document:export' LIMIT 1",
            String.class,
            tenant
        );
        assertFalse(audit.contains("张三"));
    }

    @Test
    void documentAccessUsesCurrentRbacMembershipAndTenantIsolation() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(
            cases.create(actor, "DOC-PERM", "Document permissions", "CIVIL", "").id()
        );
        var d = documents.create(actor, c, "权限文书", "仅授权人员可读");
        var session = login();
        String path = "/api/v1/cases/" + c + "/documents/" + d.id() + "/export";
        // An administrator need not be an explicit case member.
        db.update(
            "DELETE FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
            tenant,
            c,
            user
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isOk()
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='document:read'",
            tenant,
            tenant
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isForbidden()
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            documents.list(actor, c)
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'document:read')",
            tenant,
            tenant
        );
        db.update("UPDATE sys_role SET code='LAWYER' WHERE tenant_id=? AND id=?", tenant, tenant);
        db.update(
            "INSERT INTO case_member(tenant_id,case_id,user_id,member_role) VALUES(?,?,?,'READER')",
            tenant,
            c,
            user
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isOk()
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            saveDocument(actor, c, Long.parseLong(d.id()), "越权编辑")
        );
        db.update(
            "DELETE FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
            tenant,
            c,
            user
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isForbidden()
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            documents.getExportData(
                new com.lexflow.service.identity.Identity(user, other, 0, "Other"),
                c,
                Long.parseLong(d.id()),
                null
            )
        );
    }

    @Test
    void unreviewedAiCurrentVersionCannotBeExportedButApprovedVersionCan() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(cases.create(actor, "AI-EXPORT", "AI export", "CIVIL", "").id());
        var d = documents.create(actor, c, "待复核文书", "手动原稿");
        db.update(
            "UPDATE document_version SET source='AI_DRAFT',review_status='PENDING_REVIEW' WHERE tenant_id=? AND id=?",
            tenant,
            d.currentVersionId()
        );
        var session = login();
        String path = "/api/v1/cases/" + c + "/documents/" + d.id() + "/export";
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isConflict()
        );
        db.update(
            "UPDATE document_version SET review_status='REJECTED' WHERE tenant_id=? AND id=?",
            tenant,
            d.currentVersionId()
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isConflict()
        );
        documents.reviewVersion(
            actor,
            c,
            Long.parseLong(d.id()),
            Long.parseLong(d.currentVersionId()),
            "APPROVED"
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value()))
            .andExpect(status().isOk())
            .andExpect(content().string("手动原稿"));
        db.update("UPDATE case_info SET status='ARCHIVED' WHERE tenant_id=? AND id=?", tenant, c);
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isOk()
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            saveDocument(actor, c, Long.parseLong(d.id()), "归档后编辑")
        );
    }

    @Test
    void newSavedOrRestoredDocumentVersionRequiresPublicationAgain() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(
            cases.create(actor, "DOC-STATE", "Document publication", "CIVIL", "").id()
        );
        var d = documents.create(actor, c, "发布状态", "第一版");
        long id = Long.parseLong(d.id());
        assertEquals("PUBLISHED", publishDocument(actor, c, id).status());
        var next = saveDocument(actor, c, id, "第二版");
        assertEquals("DRAFT", documents.list(actor, c).getFirst().status());
        assertEquals(
            "第二版",
            documents.getExportData(actor, c, id, Long.parseLong(next.id())).content()
        );
        publishDocument(actor, c, id);
        var restored = restoreDocument(actor, c, id, Long.parseLong(d.currentVersionId()));
        var current = documents.list(actor, c).getFirst();
        assertEquals("DRAFT", current.status());
        assertEquals(restored.id(), current.currentVersionId());
        assertEquals(3, current.currentVersionNo());
        assertEquals(
            "第一版",
            documents.getExportData(actor, c, id, Long.parseLong(restored.id())).content()
        );
    }

    private record GenerationFixture(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long documentId,
        long versionId
    ) {}

    private GenerationFixture generationFixture(String suffix) {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(
            cases.create(actor, "GENERATE-" + suffix, "生成测试", "CIVIL", "甲欠乙 100 元。").id()
        );
        var d = documents.create(actor, c, "催款函", "已核对的原始正文");
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "draft-test",
            "fake-generation-key"
        );
        return new GenerationFixture(
            actor,
            c,
            Long.parseLong(d.id()),
            Long.parseLong(d.currentVersionId())
        );
    }

    @Test
    void generationRequiresCostConsentAndImmutableInputWhileReplayingOneOutbox() throws Exception {
        var f = generationFixture("REQUEST");
        var s = login();
        String path =
            "/api/v1/cases/" + f.caseId() + "/documents/" + f.documentId() + "/generations";
        String withoutConsent = json.writeValueAsString(
            java.util.Map.of(
                "sourceVersionId",
                f.versionId(),
                "instructions",
                "起草催款函",
                "idempotencyKey",
                "generation-request"
            )
        );
        perform(
            post(path)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(withoutConsent)
        ).andExpect(status().isBadRequest());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM document_generation WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        String body = json.writeValueAsString(
            java.util.Map.of(
                "sourceVersionId",
                f.versionId(),
                "instructions",
                "起草催款函",
                "idempotencyKey",
                "generation-request",
                "confirmModelCost",
                true
            )
        );
        var response = perform(
            post(path)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        )
            .andExpect(status().isAccepted())
            .andReturn();
        long id = json
            .readTree(response.getResponse().getContentAsString())
            .at("/data/taskId")
            .asLong();
        saveDocument(f.actor(), f.caseId(), f.documentId(), "提交后的新正文");
        perform(
            post(path)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.taskId").value(Long.toString(id)));
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            generations.request(
                f.actor(),
                f.caseId(),
                f.documentId(),
                f.versionId(),
                "改变输入",
                "generation-request"
            )
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            generations.request(
                f.actor(),
                f.caseId(),
                f.documentId(),
                f.versionId(),
                "起草催款函",
                "generation-stale-version"
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        String event = db.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=?",
            String.class,
            tenant
        );
        assertFalse(event.contains("fake-generation-key"));
        assertFalse(event.contains("已核对的原始正文"));
        assertEquals(
            response.getResponse().getHeader("X-Trace-Id"),
            json.readTree(event).path("traceId").asText()
        );
        var input = generations.begin(id, 0, 1);
        assertEquals(2, input.stateVersion());
        assertEquals("已核对的原始正文", input.baseContent());
        assertFalse(input.toString().contains("fake-generation-key"));
    }

    @Test
    void generationResultSavesOnePendingVersionWithoutOverwritingLaterEditsAndCanBeReviewed() {
        var f = generationFixture("RESULT");
        long id = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草催款函",
                    "generation-result"
                )
                .taskId()
        );
        generations.begin(id, 0, 1);
        var later = saveDocument(f.actor(), f.caseId(), f.documentId(), "最新人工正文");
        assertFalse(
            generations.apply(tenant, id, 0, 1, "SUCCEEDED", "旧状态结果", java.util.List.of())
        );
        assertTrue(
            generations.apply(
                tenant,
                id,
                0,
                2,
                "SUCCEEDED",
                "待复核的催款函",
                java.util.List.of("收件地址")
            )
        );
        assertFalse(
            generations.apply(tenant, id, 0, 2, "SUCCEEDED", "重复结果", java.util.List.of())
        );
        var result = generations.view(f.actor(), id).result();
        assertNotNull(result);
        assertEquals(3, result.versionNo());
        assertEquals("AI_DRAFT", result.source());
        assertEquals("PENDING_REVIEW", result.reviewStatus());
        assertEquals(Long.toString(user), result.createdBy());
        assertEquals(
            later.id(),
            documents.list(f.actor(), f.caseId()).getFirst().currentVersionId()
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM document_version WHERE tenant_id=? AND source_task_id=?",
                Integer.class,
                tenant,
                id
            )
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            restoreDocument(f.actor(), f.caseId(), f.documentId(), Long.parseLong(result.id()))
        );
        documents.reviewVersion(
            f.actor(),
            f.caseId(),
            f.documentId(),
            Long.parseLong(result.id()),
            "APPROVED"
        );
        assertEquals(
            "待复核的催款函",
            documents
                .getExportData(f.actor(), f.caseId(), f.documentId(), Long.parseLong(result.id()))
                .content()
        );
        assertEquals("DRAFT", documents.list(f.actor(), f.caseId()).getFirst().status());
    }

    @Test
    void generationCancellationAndUncertainInvocationNeverPublishOrAutomaticallyReopen() {
        var f = generationFixture("CANCEL");
        long id = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-cancel"
                )
                .taskId()
        );
        generations.begin(id, 0, 1);
        assertEquals("CANCELLED", taskService.requestCancel(f.actor(), id).status());
        assertFalse(
            generations.apply(tenant, id, 0, 2, "SUCCEEDED", "迟到结果", java.util.List.of())
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            generations.begin(id, 0, 1)
        );
        long uncertain = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-uncertain"
                )
                .taskId()
        );
        generations.begin(uncertain, 0, 1);
        assertTrue(generations.apply(tenant, uncertain, 0, 2, "UNCERTAIN", null, null));
        assertEquals("WAITING_HUMAN", generations.view(f.actor(), uncertain).status());
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            generations.recover(f.actor(), uncertain, 3)
        );
        assertEquals("CANCELLED", taskService.requestCancel(f.actor(), uncertain).status());
        assertEquals(1, documents.listVersions(f.actor(), f.caseId(), f.documentId()).size());
    }

    @Test
    void generationRejectsCrossTenantRevokedCreatorChangedModelAndArchivedCaseAtPublication() {
        var f = generationFixture("AUTH");
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.request(
                new com.lexflow.service.identity.Identity(user, other, 0, "Other"),
                f.caseId(),
                f.documentId(),
                f.versionId(),
                "起草",
                "wrong-tenant"
            )
        );
        long revoked = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-revoked"
                )
                .taskId()
        );
        generations.begin(revoked, 0, 1);
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='ai:generate'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.apply(tenant, revoked, 0, 2, "SUCCEEDED", "不能发布", java.util.List.of())
        );
        assertEquals(1, documents.listVersions(f.actor(), f.caseId(), f.documentId()).size());
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'ai:generate')",
            tenant,
            tenant
        );
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "draft-test",
            "replacement-generation-key"
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            generations.input(revoked, 0, 2)
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            generations.apply(tenant, revoked, 0, 2, "SUCCEEDED", "旧配置结果", java.util.List.of())
        );
        long archived = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-archived"
                )
                .taskId()
        );
        generations.begin(archived, 0, 1);
        db.update(
            "UPDATE case_info SET status='ARCHIVED' WHERE tenant_id=? AND id=?",
            tenant,
            f.caseId()
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.apply(tenant, archived, 0, 2, "SUCCEEDED", "归档结果", java.util.List.of())
        );
        assertEquals(1, documents.listVersions(f.actor(), f.caseId(), f.documentId()).size());
    }

    @Test
    void generationRecoveryDoesNotIncreaseAttemptAndGenericCallbacksCannotForgeResults()
        throws Exception {
        var f = generationFixture("RECOVER");
        long id = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-recover"
                )
                .taskId()
        );
        generations.begin(id, 0, 1);
        assertEquals(2, generations.begin(id, 0, 1).stateVersion());
        assertEquals(2, generations.recover(f.actor(), id, 2).stateVersion());
        assertEquals(0, taskService.get(f.actor(), id).attempt());
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                id
            )
        );
        String forged = json.writeValueAsString(
            java.util.Map.of("attempt", 0, "stateVersion", 2, "success", true)
        );
        perform(
            post("/internal/v1/tasks/" + id + "/results")
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(forged)
        ).andExpect(status().isBadRequest());
        perform(
            get("/internal/v1/document-generations/" + id + "/input")
                .param("attempt", "0")
                .param("stateVersion", "2")
        ).andExpect(status().isUnauthorized());
        assertEquals("RUNNING", taskService.get(f.actor(), id).status());
    }

    @Test
    void generationSourceAndResultRespectDocumentPermissionsAndTaskOwnership() {
        var f = generationFixture("READ");
        var foreign = documents.create(f.actor(), f.caseId(), "其他文书", "独立正文");
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.request(
                f.actor(),
                f.caseId(),
                f.documentId(),
                Long.parseLong(foreign.currentVersionId()),
                "起草",
                "generation-wrong-document"
            )
        );
        long task = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-read"
                )
                .taskId()
        );
        generations.begin(task, 0, 1);
        generations.apply(tenant, task, 0, 2, "SUCCEEDED", "待复核正文", java.util.List.of());
        long member = 900000183L;
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'generation-reader-only','unused-test-hash','Synthetic reader')",
            member
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, member);
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'LAWYER','Synthetic reader')",
            member,
            tenant
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            tenant,
            member,
            member
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            tenant,
            member
        );
        db.update(
            "INSERT INTO case_member(tenant_id,case_id,user_id,member_role) VALUES(?,?,?,'READER')",
            tenant,
            f.caseId(),
            member
        );
        var reader = new com.lexflow.service.identity.Identity(
            member,
            tenant,
            0,
            "Synthetic reader"
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.view(reader, task)
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.request(
                reader,
                f.caseId(),
                f.documentId(),
                f.versionId(),
                "起草",
                "generation-reader-submit"
            )
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='document:read'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            generations.view(f.actor(), task)
        );
    }

    @Test
    void unreviewedAiRestoreAndPublishCannotBypassExportReview() {
        var f = generationFixture("GUARD");
        long id = Long.parseLong(
            generations
                .request(
                    f.actor(),
                    f.caseId(),
                    f.documentId(),
                    f.versionId(),
                    "起草",
                    "generation-guard"
                )
                .taskId()
        );
        generations.begin(id, 0, 1);
        generations.apply(tenant, id, 0, 2, "SUCCEEDED", "合成 AI 正文", java.util.List.of());
        long version = Long.parseLong(generations.view(f.actor(), id).result().id());
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            restoreDocument(f.actor(), f.caseId(), f.documentId(), version)
        );
        db.update(
            "UPDATE document SET current_version_id=? WHERE tenant_id=? AND id=?",
            version,
            tenant,
            f.documentId()
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            publishDocument(f.actor(), f.caseId(), f.documentId())
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            documents.getExportData(f.actor(), f.caseId(), f.documentId(), version)
        );
        documents.reviewVersion(f.actor(), f.caseId(), f.documentId(), version, "REJECTED");
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            restoreDocument(f.actor(), f.caseId(), f.documentId(), version)
        );
    }

    @Test
    void missingOrForeignDocumentCurrentVersionDoesNotExportEmptyOrUnrelatedContent()
        throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(
            cases.create(actor, "DOC-BOUND", "Document binding", "CIVIL", "").id()
        );
        var a = documents.create(actor, c, "文书甲", "甲正文");
        var b = documents.create(actor, c, "文书乙", "乙正文");
        var session = login();
        String path = "/api/v1/cases/" + c + "/documents/" + a.id() + "/export";
        db.update(
            "UPDATE document SET current_version_id=NULL WHERE tenant_id=? AND id=?",
            tenant,
            a.id()
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isConflict()
        );
        db.update(
            "UPDATE document SET current_version_id=? WHERE tenant_id=? AND id=?",
            b.currentVersionId(),
            tenant,
            a.id()
        );
        perform(get(path).accept("text/plain, application/json").cookie(session.value())).andExpect(
            status().isConflict()
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:export'",
                Integer.class,
                tenant
            )
        );
    }

    private record ContractFixture(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long fileId,
        long versionId
    ) {}

    private ContractFixture contractFixture() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(cases.create(actor, "CONTRACT", "Contract case", "CIVIL", "").id());
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'contract.txt','AVAILABLE',?)",
            tenant,
            c,
            user
        );
        long f = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            c
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,size_bytes,mime_type,sha256) VALUES(?,?,1,'test-only-contract',30,'text/plain',?)",
            tenant,
            f,
            "f".repeat(64)
        );
        long v = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            f
        );
        return new ContractFixture(actor, c, f, v);
    }

    private com.lexflow.service.vo.ContractReport contractReport(String hash) {
        return new com.lexflow.service.vo.ContractReport(
            hash,
            "验收后付款。",
            "服务合同",
            java.util.List.of(
                new com.lexflow.service.vo.ContractReport.Clause("1", "付款", "验收后付款。", 0, 6)
            ),
            java.util.List.of(
                new com.lexflow.service.vo.ContractReport.Risk(
                    "1",
                    "HIGH",
                    "期限不清楚",
                    "补充付款期限",
                    java.util.List.of()
                )
            ),
            java.util.List.of(),
            java.util.List.of(),
            true
        );
    }

    @Test
    void contractSubmitRequiresManualKeyAndPreservesImmutableIdempotentInput() throws Exception {
        var f = contractFixture();
        var s = login();
        String key = java.util.UUID.randomUUID().toString();
        String body = json.writeValueAsString(
            java.util.Map.of(
                "caseId",
                f.caseId(),
                "fileVersionId",
                f.versionId(),
                "stance",
                "PARTY_A",
                "focus",
                "付款",
                "idempotencyKey",
                key
            )
        );
        perform(
            post("/api/v1/contract-reviews")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isConflict());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM async_task WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "fake-contract-key"
        );
        String response = perform(
            post("/api/v1/contract-reviews")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn()
            .getResponse()
            .getContentAsString();
        long id = json.readTree(response).at("/data/taskId").asLong();
        assertFalse(response.contains("fake-contract-key"));
        perform(
            post("/api/v1/contract-reviews")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(body)
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.taskId").value(Long.toString(id)));
        assertEquals(
            Long.toString(id),
            contractReviews
                .request(f.actor(), f.caseId(), f.versionId(), "PARTY_A", "付款", null, key)
                .taskId()
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            contractReviews.request(
                f.actor(),
                f.caseId(),
                f.versionId(),
                "PARTY_B",
                "付款",
                null,
                key
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                id
            )
        );
        String event = db.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE tenant_id=? AND task_id=?",
            String.class,
            tenant,
            id
        );
        assertFalse(event.contains("fake-contract-key"));
        assertEquals(1, json.readTree(event).path("stateVersion").asInt());
        perform(
            get("/internal/v1/contract-reviews/" + id + "/input")
                .param("attempt", "0")
                .param("stateVersion", "1")
        ).andExpect(status().isUnauthorized());
        var input = contractReviews.begin(id, 0, 1);
        assertEquals(2, input.stateVersion());
        assertEquals("fake-contract-key", input.apiKey());
        assertEquals(2, contractReviews.begin(id, 0, 2).stateVersion());
    }

    @Test
    void contractReportReviewDecisionAndResumeAreAtomicAndLateResultsAreIgnored() throws Exception {
        var f = contractFixture();
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "fake-contract-key"
        );
        long id = Long.parseLong(
            contractReviews
                .request(
                    f.actor(),
                    f.caseId(),
                    f.versionId(),
                    "PARTY_A",
                    "付款",
                    null,
                    java.util.UUID.randomUUID().toString()
                )
                .taskId()
        );
        contractReviews.begin(id, 0, 1);
        var report = contractReport("f".repeat(64));
        assertTrue(contractReviews.apply(tenant, id, 0, 2, "WAITING_HUMAN", report));
        assertFalse(contractReviews.apply(tenant, id, 0, 2, "SUCCEEDED", report));
        assertEquals("WAITING_HUMAN", contractReviews.view(f.actor(), id).status());
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            contractReviews.approve(
                f.actor(),
                id,
                2,
                java.util.UUID.randomUUID().toString(),
                "已核对"
            )
        );
        String decision = java.util.UUID.randomUUID().toString();
        assertEquals(
            4,
            contractReviews
                .approve(f.actor(), id, 3, decision, "已核对原文，需继续补充法规依据")
                .stateVersion()
        );
        assertEquals(
            4,
            contractReviews
                .approve(f.actor(), id, 3, decision, "已核对原文，需继续补充法规依据")
                .stateVersion()
        );
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=?",
                Integer.class,
                tenant,
                id
            )
        );
        assertEquals(decision, contractReviews.input(id, 0, 4).decisionId());
        assertTrue(contractReviews.apply(tenant, id, 0, 4, "SUCCEEDED", report));
        assertFalse(contractReviews.apply(tenant, id, 0, 4, "FAILED", null));
        var view = contractReviews.view(f.actor(), id);
        assertEquals("SUCCEEDED", view.status());
        assertEquals(Long.toString(user), view.decidedBy());
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            contractReviews.view(
                new com.lexflow.service.identity.Identity(user, other, 0, "Other"),
                id
            )
        );
        var s = login();
        String publicResult = perform(get("/api/v1/contract-reviews/" + id).cookie(s.value()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertTrue(publicResult.contains("验收后付款"));
        assertFalse(publicResult.contains("fake-contract-key"));
    }

    @Test
    void contractCancellationAndSourceDeletionPreventLatePublicationAndReview() {
        var f = contractFixture();
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "fake-key"
        );
        long id = Long.parseLong(
            contractReviews
                .request(
                    f.actor(),
                    f.caseId(),
                    f.versionId(),
                    "NEUTRAL",
                    "",
                    null,
                    java.util.UUID.randomUUID().toString()
                )
                .taskId()
        );
        contractReviews.begin(id, 0, 1);
        assertTrue(
            contractReviews.apply(tenant, id, 0, 2, "WAITING_HUMAN", contractReport("f".repeat(64)))
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            tenant,
            f.fileId()
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            contractReviews.approve(
                f.actor(),
                id,
                3,
                java.util.UUID.randomUUID().toString(),
                "已核对"
            )
        );
        assertEquals("CANCELLED", taskService.requestCancel(f.actor(), id).status());
        assertFalse(
            contractReviews.apply(tenant, id, 0, 3, "SUCCEEDED", contractReport("f".repeat(64)))
        );
        assertEquals("CANCELLED", taskService.get(f.actor(), id).status());
    }

    @Test
    void contractInvalidResultsAndWithdrawnPermissionCannotPublish() {
        var f = contractFixture();
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "fake-key"
        );
        long id = Long.parseLong(
            contractReviews
                .request(
                    f.actor(),
                    f.caseId(),
                    f.versionId(),
                    "NEUTRAL",
                    "",
                    null,
                    java.util.UUID.randomUUID().toString()
                )
                .taskId()
        );
        contractReviews.begin(id, 0, 1);
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            contractReviews.apply(tenant, id, 0, 2, "WAITING_HUMAN", contractReport("e".repeat(64)))
        );
        assertThrows(IllegalArgumentException.class, () ->
            taskResults.applyResult(id, 0, 2, true, null, null)
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='ai:review'",
            tenant,
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            contractReviews.input(id, 0, 2)
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            contractReviews.apply(tenant, id, 0, 2, "WAITING_HUMAN", contractReport("f".repeat(64)))
        );
        assertEquals(
            "RUNNING",
            db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                id
            )
        );
    }

    @Test
    void uncertainContractCallCannotBeApprovedOrReissuedByRecovery() {
        var f = contractFixture();
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "fake-key"
        );
        long id = Long.parseLong(
            contractReviews
                .request(
                    f.actor(),
                    f.caseId(),
                    f.versionId(),
                    "NEUTRAL",
                    "",
                    null,
                    java.util.UUID.randomUUID().toString()
                )
                .taskId()
        );
        contractReviews.begin(id, 0, 1);
        assertTrue(contractReviews.apply(tenant, id, 0, 2, "UNCERTAIN", null));
        assertEquals("UNCERTAIN", contractReviews.view(f.actor(), id).waitingReason());
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            contractReviews.approve(
                f.actor(),
                id,
                3,
                java.util.UUID.randomUUID().toString(),
                "已核对"
            )
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            contractReviews.recover(f.actor(), id, 3)
        );
        assertEquals("CANCELLED", taskService.requestCancel(f.actor(), id).status());
    }

    @Test
    void contractKnowledgeBaseIdIsReturnedAsStringWithoutJavaScriptNumberConversion()
        throws Exception {
        var f = contractFixture();
        aiConfigurations.save(
            tenant,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "fake-key"
        );
        long base = Long.parseLong(
            knowledgeService.create(f.actor(), f.caseId(), "synthetic reference").id()
        );
        long id = Long.parseLong(
            contractReviews
                .request(
                    f.actor(),
                    f.caseId(),
                    f.versionId(),
                    "NEUTRAL",
                    "",
                    base,
                    java.util.UUID.randomUUID().toString()
                )
                .taskId()
        );
        var response = perform(
            get("/internal/v1/contract-reviews/" + id + "/input")
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .param("attempt", "0")
                .param("stateVersion", "1")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.knowledgeBaseId").value(Long.toString(base)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertTrue(json.readTree(response).path("knowledgeBaseId").isTextual());
    }

    @Test
    void rerankKeyIsManualTenantScopedAndNeverReturnedByPublicConfig() throws Exception {
        assertNull(aiConfigurations.rerankForSearch(tenant));
        aiConfigurations.save(
            tenant,
            "RERANK",
            "test",
            "https://models.example.test/v2",
            "rerank-test",
            "fake-rank-key"
        );
        assertEquals("fake-rank-key", aiConfigurations.rerankForSearch(tenant).apiKey());
        assertNull(aiConfigurations.rerankForSearch(other));
        String encrypted = db.queryForObject(
            "SELECT encrypted_key FROM ai_configuration WHERE tenant_id=? AND purpose='RERANK'",
            String.class,
            tenant
        );
        assertFalse(encrypted.contains("fake-rank-key"));
        String publicJson = json.writeValueAsString(aiConfigurations.list(tenant));
        assertFalse(publicJson.contains("fake-rank-key"));
        assertFalse(publicJson.contains(encrypted));
        aiConfigurations.delete(tenant, "RERANK");
        assertNull(aiConfigurations.rerankForSearch(tenant));
    }

    private record IndexFixture(
        com.lexflow.service.identity.Identity actor,
        long caseId,
        long baseId,
        long documentId,
        long fileId
    ) {}

    private IndexFixture indexFixture() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long c = Long.parseLong(cases.create(actor, "REBUILD", "Rebuild case", "CIVIL", "").id());
        String text = "付款条款：验收后付款。";
        String hash = "e".repeat(64);
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'rebuild.txt','AVAILABLE',?)",
            tenant,
            c,
            user
        );
        long f = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            c
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            tenant,
            f,
            "rebuild-" + java.util.UUID.randomUUID(),
            hash,
            text.length()
        );
        long v = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            f
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'rebuild-parse','SUCCEEDED',?)",
            tenant,
            c,
            v,
            user
        );
        long parse = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key='rebuild-parse'",
            Long.class,
            tenant
        );
        String segments = json.writeValueAsString(
            java.util.List.of(
                java.util.Map.of(
                    "kind",
                    "PARAGRAPH",
                    "index",
                    1,
                    "startOffset",
                    0,
                    "endOffset",
                    text.length()
                )
            )
        );
        db.update(
            "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,?,?)",
            tenant,
            parse,
            hash,
            text,
            segments
        );
        long b = Long.parseLong(knowledgeService.create(actor, c, "Rebuild knowledge").id());
        long d = Long.parseLong(knowledgeService.prepare(actor, b, parse).id());
        aiConfigurations.save(
            tenant,
            "EMBEDDING",
            "test",
            "https://models.example.test/v1",
            "embed-v1",
            "fake-key"
        );
        var fixture = new IndexFixture(actor, c, b, d, f);
        long first = Long.parseLong(knowledgeIndexes.request(actor, b, d).taskId());
        assertTrue(publishIndex(first));
        return fixture;
    }

    private boolean publishIndex(long taskId) throws Exception {
        var input = knowledgeIndexes.input(taskId, 0, 1);
        String identity = json.writeValueAsString(
            java.util.List.of(input.provider(), input.baseUrl(), input.model())
        );
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(
            identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String collection =
            "lexflow_chunks_" + java.util.HexFormat.of().formatHex(digest, 0, 8) + "_4_v1";
        return knowledgeIndexes.complete(
            taskId,
            0,
            1,
            true,
            collection,
            4,
            input.chunks().size(),
            identity
        );
    }

    private long rebuild(IndexFixture f) {
        return Long.parseLong(
            knowledgeIndexes.request(f.actor(), f.baseId(), f.documentId()).taskId()
        );
    }

    private void assertPublished(IndexFixture f, int version, Long pending) {
        var row = knowledgeRepository.indexDocument(tenant, f.baseId(), f.documentId());
        assertEquals("ACTIVE", row.status());
        assertEquals(version, row.indexVersion());
        assertEquals(pending, row.pendingTaskId());
        assertEquals(
            version,
            knowledgeRepository
                .searchSources(tenant, f.baseId(), f.caseId())
                .getFirst()
                .indexVersion()
        );
    }

    @Test
    void rebuildPublishesNewModelOnlyAfterSuccessWithDistinctChunkIds() throws Exception {
        var f = indexFixture();
        var old = knowledgeRepository.searchSources(tenant, f.baseId(), f.caseId()).getFirst();
        long oldChunk = knowledgeRepository.indexChunks(tenant, f.documentId(), 1).getFirst().id();
        aiConfigurations.save(
            tenant,
            "EMBEDDING",
            "test",
            "https://models.example.test/v1",
            "embed-v2",
            "fake-key-v2"
        );
        long task = rebuild(f);
        var input = knowledgeIndexes.input(task, 0, 1);
        assertPublished(f, 1, task);
        assertEquals(2, input.indexVersion());
        assertEquals(
            old.modelIdentity(),
            knowledgeRepository
                .searchSources(tenant, f.baseId(), f.caseId())
                .getFirst()
                .modelIdentity()
        );
        assertNotEquals(Long.toString(oldChunk), input.chunks().getFirst().id());
        assertNotNull(
            knowledgeRepository.citation(
                tenant,
                f.baseId(),
                f.caseId(),
                f.documentId(),
                oldChunk,
                old.collectionName(),
                1
            )
        );
        assertNull(
            knowledgeRepository.citation(
                tenant,
                f.baseId(),
                f.caseId(),
                f.documentId(),
                Long.parseLong(input.chunks().getFirst().id()),
                old.collectionName(),
                2
            )
        );
        assertEquals(
            Long.toString(task),
            knowledgeService.documents(f.actor(), f.baseId()).getFirst().pendingTaskId()
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () -> rebuild(f));
        assertTrue(publishIndex(task));
        assertPublished(f, 2, null);
        var published = knowledgeRepository
            .searchSources(tenant, f.baseId(), f.caseId())
            .getFirst();
        assertNotEquals(old.collectionName(), published.collectionName());
        assertNull(
            knowledgeRepository.citation(
                tenant,
                f.baseId(),
                f.caseId(),
                f.documentId(),
                oldChunk,
                old.collectionName(),
                1
            )
        );
        assertNotNull(
            knowledgeRepository.citation(
                tenant,
                f.baseId(),
                f.caseId(),
                f.documentId(),
                Long.parseLong(input.chunks().getFirst().id()),
                published.collectionName(),
                2
            )
        );
        assertFalse(knowledgeIndexes.complete(task, 0, 1, false, null, null, null, null));
        assertPublished(f, 2, null);
    }

    @Test
    void publishedVersionIsNeverReusedEvenWhenLegacyAllocationMetadataIsMissing() throws Exception {
        var f = indexFixture();
        db.update("UPDATE knowledge_document SET last_index_version=0 WHERE id=?", f.documentId());
        long task = rebuild(f);
        var input = knowledgeIndexes.input(task, 0, 1);
        assertEquals(2, input.indexVersion());
        assertPublished(f, 1, task);
        assertNotEquals(
            Long.toString(
                knowledgeRepository.indexChunks(tenant, f.documentId(), 1).getFirst().id()
            ),
            input.chunks().getFirst().id()
        );
        assertTrue(publishIndex(task));
        assertPublished(f, 2, null);
    }

    @Test
    void cancelledRebuildRetainsPublishedIndexAndLateCallbacksCannotChangeNextJob()
        throws Exception {
        var f = indexFixture();
        long cancelled = rebuild(f);
        var input = knowledgeIndexes.input(cancelled, 0, 1);
        assertEquals("CANCELLED", taskService.requestCancel(f.actor(), cancelled).status());
        assertPublished(f, 1, null);
        long next = rebuild(f);
        var nextInput = knowledgeIndexes.input(next, 0, 1);
        assertEquals(3, nextInput.indexVersion());
        assertNotEquals(input.chunks().getFirst().id(), nextInput.chunks().getFirst().id());
        assertFalse(knowledgeIndexes.complete(cancelled, 0, 1, true, "invalid", 4, 1, "invalid"));
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            knowledgeIndexes.input(cancelled, 0, 1)
        );
        assertThrows(IllegalStateException.class, () ->
            taskService.requestCancel(f.actor(), cancelled)
        );
        assertPublished(f, 1, next);
        assertTrue(publishIndex(next));
        assertPublished(f, 3, null);
    }

    @Test
    void failedRebuildAndChangedConfigurationRetainPublishedMetadata() throws Exception {
        var f = indexFixture();
        var old = knowledgeRepository.searchSources(tenant, f.baseId(), f.caseId()).getFirst();
        long failed = rebuild(f);
        assertTrue(knowledgeIndexes.complete(failed, 0, 1, false, null, null, null, null));
        assertPublished(f, 1, null);
        assertEquals(
            old,
            knowledgeRepository.searchSources(tenant, f.baseId(), f.caseId()).getFirst()
        );
        long changed = rebuild(f);
        var input = knowledgeIndexes.input(changed, 0, 1);
        aiConfigurations.save(
            tenant,
            "EMBEDDING",
            "test",
            "https://models.example.test/v1",
            "changed-model",
            "fake-key"
        );
        assertTrue(
            knowledgeIndexes.complete(
                changed,
                0,
                1,
                true,
                old.collectionName(),
                4,
                input.chunks().size(),
                old.modelIdentity()
            )
        );
        assertEquals(
            "FAILED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, changed)
        );
        assertPublished(f, 1, null);
        assertEquals(
            old,
            knowledgeRepository.searchSources(tenant, f.baseId(), f.caseId()).getFirst()
        );
    }

    @Test
    void rebuildRejectsRevokedPermissionOrDeletedSourceBeforePublication() throws Exception {
        var f = indexFixture();
        var old = knowledgeRepository.searchSources(tenant, f.baseId(), f.caseId()).getFirst();
        long revoked = rebuild(f);
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='knowledge:manage'",
            tenant
        );
        assertTrue(
            knowledgeIndexes.complete(
                revoked,
                0,
                1,
                true,
                old.collectionName(),
                4,
                1,
                old.modelIdentity()
            )
        );
        assertPublished(f, 1, null);
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () -> rebuild(f));
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'knowledge:manage')",
            tenant,
            tenant
        );
        long deleted = rebuild(f);
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            tenant,
            f.fileId()
        );
        assertTrue(
            knowledgeIndexes.complete(
                deleted,
                0,
                1,
                true,
                old.collectionName(),
                4,
                1,
                old.modelIdentity()
            )
        );
        assertEquals(
            "ACTIVE",
            db.queryForObject(
                "SELECT status FROM knowledge_document WHERE id=?",
                String.class,
                f.documentId()
            )
        );
        assertNull(
            db.queryForObject(
                "SELECT pending_index_task_id FROM knowledge_document WHERE id=?",
                Long.class,
                f.documentId()
            )
        );
        assertTrue(knowledgeRepository.searchSources(tenant, f.baseId(), f.caseId()).isEmpty());
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () -> rebuild(f));
    }

    @Test
    void knowledgeIndexRequiresManualKeyAndPublishesOnlyCurrentAuthorizedResult() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long caseId = Long.parseLong(
            cases.create(actor, "INDEX-CASE", "Index case", "CIVIL", "").id()
        );
        String text = "付款条款：验收后付款。";
        String hash = "c".repeat(64);
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'contract.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            tenant,
            fileId,
            "index-test-" + java.util.UUID.randomUUID(),
            hash,
            text.length()
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            fileId
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'index-parse','SUCCEEDED',?)",
            tenant,
            caseId,
            version,
            user
        );
        long parseTask = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key='index-parse'",
            Long.class,
            tenant
        );
        String segments = json.writeValueAsString(
            java.util.List.of(
                java.util.Map.of(
                    "kind",
                    "PARAGRAPH",
                    "index",
                    1,
                    "startOffset",
                    0,
                    "endOffset",
                    text.length()
                )
            )
        );
        db.update(
            "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,?,?)",
            tenant,
            parseTask,
            hash,
            text,
            segments
        );
        long baseId = Long.parseLong(knowledgeService.create(actor, caseId, "索引测试").id());
        long documentId = Long.parseLong(knowledgeService.prepare(actor, baseId, parseTask).id());

        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            knowledgeIndexes.request(actor, baseId, documentId)
        );
        assertEquals(
            "PREPARED",
            db.queryForObject(
                "SELECT status FROM knowledge_document WHERE id=?",
                String.class,
                documentId
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM knowledge_index_job WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );

        aiConfigurations.save(
            tenant,
            "EMBEDDING",
            "test",
            "https://models.example.test/v1",
            "embed-v1",
            "fake-user-key"
        );
        var indexSession = login();
        var indexed = perform(
            post("/api/v1/knowledge-bases/" + baseId + "/documents/" + documentId + "/index")
                .cookie(indexSession.value())
                .header("X-CSRF-TOKEN", indexSession.token())
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        long indexTask = json
            .readTree(indexed.getResponse().getContentAsString())
            .at("/data/taskId")
            .asLong();
        assertEquals(
            "INDEXING",
            db.queryForObject(
                "SELECT status FROM knowledge_document WHERE id=?",
                String.class,
                documentId
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=? AND routing_key='knowledge.ingest.requested.v1'",
                Integer.class,
                tenant,
                indexTask
            )
        );
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            knowledgeIndexes.request(actor, baseId, documentId)
        );
        String inputPath = "/internal/v1/knowledge-index/" + indexTask + "/input";
        perform(get(inputPath).param("attempt", "0").param("stateVersion", "1")).andExpect(
            status().isUnauthorized()
        );
        perform(
            get(inputPath)
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .param("attempt", "0")
                .param("stateVersion", "1")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKey").value("fake-user-key"))
            .andExpect(jsonPath("$.chunks[0].text").value(text));
        long cancelledTask = indexTask;
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            knowledgeIndexes.input(cancelledTask, 1, 1)
        );
        assertEquals("CANCELLED", taskService.requestCancel(actor, cancelledTask).status());
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM knowledge_document WHERE id=?",
                String.class,
                documentId
            )
        );
        assertFalse(
            knowledgeIndexes.complete(cancelledTask, 0, 1, true, "invalid", 4, 1, "invalid")
        );
        indexTask = Long.parseLong(knowledgeIndexes.request(actor, baseId, documentId).taskId());
        assertNotEquals(cancelledTask, indexTask);
        assertEquals(
            "INDEXING",
            db.queryForObject(
                "SELECT status FROM knowledge_document WHERE id=?",
                String.class,
                documentId
            )
        );

        String identity = json.writeValueAsString(
            java.util.List.of("test", "https://models.example.test/v1", "embed-v1")
        );
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
            identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String collection =
            "lexflow_chunks_" + java.util.HexFormat.of().formatHex(digest, 0, 8) + "_4_v1";
        String result = json.writeValueAsString(
            java.util.Map.of(
                "attempt",
                0,
                "stateVersion",
                1,
                "success",
                true,
                "collectionName",
                collection,
                "dimension",
                4,
                "indexedChunks",
                1,
                "modelIdentity",
                identity
            )
        );
        perform(
            post("/internal/v1/knowledge-index/" + indexTask + "/result")
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(result)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));
        assertEquals(
            "ACTIVE",
            db.queryForObject(
                "SELECT status FROM knowledge_document WHERE id=?",
                String.class,
                documentId
            )
        );
        assertEquals(
            collection,
            db.queryForObject(
                "SELECT collection_name FROM knowledge_document WHERE id=?",
                String.class,
                documentId
            )
        );
        perform(
            post("/internal/v1/knowledge-index/" + indexTask + "/result")
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(result)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false));
        assertEquals(
            "SUCCEEDED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, indexTask)
        );
        assertEquals(1, knowledgeRepository.searchSources(tenant, baseId, caseId).size());
        int publishedVersion = db.queryForObject(
            "SELECT index_version FROM knowledge_document WHERE id=?",
            Integer.class,
            documentId
        );
        long chunkId = db.queryForObject(
            "SELECT id FROM knowledge_chunk WHERE tenant_id=? AND knowledge_document_id=? AND index_version=?",
            Long.class,
            tenant,
            documentId,
            publishedVersion
        );
        assertNotNull(
            knowledgeRepository.citation(
                tenant,
                baseId,
                caseId,
                documentId,
                chunkId,
                collection,
                publishedVersion
            )
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='ai:query'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            knowledgeSearch.search(actor, baseId, "合同付款时间")
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'ai:query')",
            tenant,
            tenant
        );
        db.update(
            "UPDATE file_object SET status='DELETED',deleted_at=NOW(6) WHERE tenant_id=? AND id=?",
            tenant,
            fileId
        );
        assertTrue(knowledgeRepository.searchSources(tenant, baseId, caseId).isEmpty());
        assertNull(
            knowledgeRepository.citation(
                tenant,
                baseId,
                caseId,
                documentId,
                chunkId,
                collection,
                publishedVersion
            )
        );
    }

    @Test
    void caseKnowledgePreparationKeepsSourceAndPermissions() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long caseId = Long.parseLong(
            cases.create(actor, "KNOWLEDGE-CASE", "Knowledge case", "CIVIL", "").id()
        );
        long otherCase = Long.parseLong(
            cases.create(actor, "KNOWLEDGE-OTHER", "Other case", "CIVIL", "").id()
        );
        String text = "A".repeat(900) + "\n\n付款条款";
        String hash = "b".repeat(64);
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'contract.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            tenant,
            fileId,
            "knowledge-test-" + java.util.UUID.randomUUID(),
            hash,
            text.length()
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            fileId
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'knowledge-parse','SUCCEEDED',?)",
            tenant,
            caseId,
            version,
            user
        );
        long taskId = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key='knowledge-parse'",
            Long.class,
            tenant
        );
        String segments =
            "[{\"kind\":\"PARAGRAPH\",\"index\":1,\"startOffset\":0,\"endOffset\":900},{\"kind\":\"PARAGRAPH\",\"index\":2,\"startOffset\":902,\"endOffset\":906}]";
        db.update(
            "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,?,?)",
            tenant,
            taskId,
            hash,
            text,
            segments
        );
        var session = login();
        String create = json.writeValueAsString(
            java.util.Map.of("caseId", caseId, "name", "案件资料")
        );
        var response = perform(
            post("/api/v1/knowledge-bases")
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(create)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope").value("CASE"))
            .andReturn();
        String baseId = json
            .readTree(response.getResponse().getContentAsString())
            .at("/data/id")
            .asText();
        String prepare = json.writeValueAsString(java.util.Map.of("parseTaskId", taskId));
        var prepared = perform(
            post("/api/v1/knowledge-bases/" + baseId + "/documents")
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(prepare)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PREPARED"))
            .andExpect(jsonPath("$.data.chunkCount").value(3))
            .andReturn();
        String documentId = json
            .readTree(prepared.getResponse().getContentAsString())
            .at("/data/id")
            .asText();
        perform(
            get(
                "/api/v1/knowledge-bases/" + baseId + "/documents/" + documentId + "/chunks"
            ).cookie(session.value())
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].locatorIndex").value(1))
            .andExpect(jsonPath("$.data[1].startOffset").value(720))
            .andExpect(jsonPath("$.data[2].text").value("付款条款"));
        perform(
            post("/api/v1/knowledge-bases/" + baseId + "/documents")
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(prepare)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(documentId));
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );

        String second = json.writeValueAsString(
            java.util.Map.of("caseId", otherCase, "name", "其他案件资料")
        );
        var otherResponse = perform(
            post("/api/v1/knowledge-bases")
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(second)
        )
            .andExpect(status().isOk())
            .andReturn();
        String otherBase = json
            .readTree(otherResponse.getResponse().getContentAsString())
            .at("/data/id")
            .asText();
        perform(
            post("/api/v1/knowledge-bases/" + otherBase + "/documents")
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(prepare)
        ).andExpect(status().isForbidden());
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='knowledge:manage'",
            tenant
        );
        perform(
            post("/api/v1/knowledge-bases")
                .cookie(session.value())
                .header("X-CSRF-TOKEN", session.token())
                .contentType("application/json")
                .content(create)
        ).andExpect(status().isForbidden());
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
            tenant
        );
        perform(
            get("/api/v1/knowledge-bases/" + baseId + "/documents").cookie(session.value())
        ).andExpect(status().isForbidden());
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'file:read')",
            tenant,
            tenant
        );
        db.update(
            "UPDATE file_object SET status='DELETED',deleted_at=NOW(6) WHERE tenant_id=? AND id=?",
            tenant,
            fileId
        );
        perform(get("/api/v1/knowledge-bases/" + baseId + "/documents").cookie(session.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
        perform(
            get(
                "/api/v1/knowledge-bases/" + baseId + "/documents/" + documentId + "/chunks"
            ).cookie(session.value())
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void taskAccessFollowsCaseAndRolePermissions() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long caseId = Long.parseLong(
            cases.create(actor, "TASK-PERMISSION", "Task permissions", "CIVIL", "").id()
        );
        db.update(
            "DELETE FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
            tenant,
            caseId,
            user
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'test.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,REPEAT('a',64),4,'text/plain')",
            tenant,
            fileId,
            "task-permission-" + java.util.UUID.randomUUID()
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            fileId
        );
        long taskId = Long.parseLong(
            taskService
                .submitTask(actor, caseId, "DOCUMENT_PARSE", version, "task-permission-test")
                .id()
        );
        assertEquals("QUEUED", taskService.get(actor, taskId).status());
        assertEquals(1, taskService.list(actor, 1, 20, null, null, null).total());

        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='task:read'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            taskService.get(actor, taskId)
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            taskService.list(actor, 1, 20, null, null, null)
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'task:read')",
            tenant,
            tenant
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            taskService.submitTask(
                actor,
                caseId,
                "DOCUMENT_PARSE",
                version,
                "task-permission-denied"
            )
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'file:read')",
            tenant,
            tenant
        );

        db.update(
            "INSERT INTO sys_role(tenant_id,code,name) VALUES(?,'LAWYER','Test Lawyer')",
            tenant
        );
        long lawyerRole = db.queryForObject(
            "SELECT id FROM sys_role WHERE tenant_id=? AND code='LAWYER'",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            tenant,
            lawyerRole
        );
        db.update("DELETE FROM user_role WHERE tenant_id=? AND user_id=?", tenant, user);
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            tenant,
            user,
            lawyerRole
        );
        assertEquals(0, taskService.list(actor, 1, 20, null, null, null).total());
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            taskService.get(actor, taskId)
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM async_task WHERE tenant_id=?",
                Integer.class,
                tenant
            )
        );
    }

    @Test
    void newlySubmittedOutboxEventIsDueOnDatabaseClock() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long caseId = Long.parseLong(
            cases.create(actor, "OUTBOX-DUE", "Outbox clock", "CIVIL", "").id()
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'test.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,REPEAT('a',64),4,'text/plain')",
            tenant,
            fileId,
            "outbox-due-" + java.util.UUID.randomUUID()
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            fileId
        );
        long taskId = Long.parseLong(
            taskService.submitTask(actor, caseId, "DOCUMENT_PARSE", version, "outbox-due-test").id()
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND task_id=? AND status='PENDING' AND next_retry_at <= NOW(6)",
                Integer.class,
                tenant,
                taskId
            )
        );
    }

    @Test
    void taskResultsDoNotOverwriteTerminalOrCancelledTasks() {
        for (String initial : java.util.List.of(
            "SUCCEEDED",
            "FAILED",
            "CANCELLED",
            "CANCEL_REQUESTED",
            "WAITING_HUMAN",
            "QUEUED",
            "RUNNING"
        )) {
            String key = "result-guard-" + initial;
            db.update(
                "INSERT INTO async_task(tenant_id,type,status,idempotency_key,created_by,attempt,state_version) VALUES(?,'DOCUMENT_PARSE',?,?,?,2,4)",
                tenant,
                initial,
                key,
                user
            );
            long id = db.queryForObject(
                "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key=?",
                Long.class,
                tenant,
                key
            );
            taskResults.applyResult(id, 1, 4, true, "stale-key", null);
            taskResults.applyResult(id, 2, 3, true, "stale-key", null);
            assertEquals(
                initial,
                db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, id)
            );
            taskResults.applyResult(id, 2, 4, false, null, "test failure");
            boolean allowed = initial.equals("QUEUED") || initial.equals("RUNNING");
            assertEquals(
                allowed ? "FAILED" : initial,
                db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, id)
            );
            assertEquals(
                allowed ? 5 : 4,
                db.queryForObject(
                    "SELECT state_version FROM async_task WHERE id=?",
                    Integer.class,
                    id
                )
            );
            taskResults.applyResult(id, 2, 4, true, "late-key", null);
            assertNull(
                db.queryForObject(
                    "SELECT result_object_key FROM async_task WHERE id=?",
                    String.class,
                    id
                )
            );
            assertEquals(
                allowed ? 1 : 0,
                db.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='task:result' AND object_id=?",
                    Integer.class,
                    tenant,
                    Long.toString(id)
                )
            );
        }
    }

    @Autowired
    com.lexflow.dao.AsyncTaskMapper asyncTasks;

    @Autowired
    com.lexflow.website.biz.TaskContextBiz taskContexts;

    @Test
    void parsedTextResultIsAtomicAndVisibleOnlyToTaskOwner() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long caseId = Long.parseLong(
            cases.create(actor, "PARSE-RESULT", "Result", "CIVIL", "").id()
        );
        byte[] input = "测试合同正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(input)
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'test.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            tenant,
            fileId,
            "parse-test-" + java.util.UUID.randomUUID(),
            hash,
            input.length
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            fileId
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'parse-result-test',?)",
            tenant,
            caseId,
            version,
            user
        );
        long taskId = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key='parse-result-test'",
            Long.class,
            tenant
        );
        var session = login();
        String endpoint = "/internal/v1/tasks/" + taskId + "/results";
        String wrong = json.writeValueAsString(
            java.util.Map.of(
                "attempt",
                0,
                "stateVersion",
                1,
                "success",
                true,
                "parsedText",
                "测试合同正文",
                "sourceSha256",
                "a".repeat(64)
            )
        );
        perform(
            post(endpoint)
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(wrong)
        ).andExpect(status().isBadRequest());
        assertEquals(
            "QUEUED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, taskId)
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_parse_result WHERE task_id=?",
                Integer.class,
                taskId
            )
        );
        String badSegments = json.writeValueAsString(
            java.util.Map.of(
                "attempt",
                0,
                "stateVersion",
                1,
                "success",
                true,
                "parsedText",
                "测试合同正文",
                "sourceSha256",
                hash,
                "segments",
                java.util.List.of(
                    java.util.Map.of(
                        "kind",
                        "PARAGRAPH",
                        "index",
                        1,
                        "startOffset",
                        0,
                        "endOffset",
                        999
                    )
                )
            )
        );
        perform(
            post(endpoint)
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(badSegments)
        ).andExpect(status().isBadRequest());
        assertEquals(
            "QUEUED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, taskId)
        );
        String right = json.writeValueAsString(
            java.util.Map.of(
                "attempt",
                0,
                "stateVersion",
                1,
                "success",
                true,
                "parsedText",
                "测试合同正文",
                "sourceSha256",
                hash,
                "segments",
                java.util.List.of(
                    java.util.Map.of(
                        "kind",
                        "PARAGRAPH",
                        "index",
                        1,
                        "startOffset",
                        0,
                        "endOffset",
                        6
                    )
                )
            )
        );
        perform(
            post(endpoint)
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(right)
        ).andExpect(status().isOk());
        perform(
            post(endpoint)
                .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                .contentType("application/json")
                .content(right)
        ).andExpect(status().isOk());
        assertEquals(
            "SUCCEEDED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, taskId)
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_parse_result WHERE task_id=?",
                Integer.class,
                taskId
            )
        );
        perform(get("/api/v1/tasks/" + taskId + "/parsed-text").cookie(session.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.text").value("测试合同正文"))
            .andExpect(jsonPath("$.data.sourceSha256").value(hash));
        perform(get("/api/v1/tasks/" + taskId + "/parsed-text").cookie(session.value()))
            .andExpect(jsonPath("$.data.segments[0].kind").value("PARAGRAPH"))
            .andExpect(jsonPath("$.data.segments[0].endOffset").value(6));
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
            tenant
        );
        perform(get("/api/v1/tasks/" + taskId + "/parsed-text").cookie(session.value())).andExpect(
            status().isForbidden()
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'file:read')",
            tenant,
            tenant
        );
        perform(get("/api/v1/tasks/" + taskId + "/parsed-text")).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void taskSourceQueryRequiresSameTenantCaseAndAvailableFile() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        long caseId = Long.parseLong(
            cases.create(actor, "TASK-SOURCE", "Source", "CIVIL", "").id()
        );
        long otherCase = Long.parseLong(
            cases.create(actor, "TASK-OTHER", "Other", "CIVIL", "").id()
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'test.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            tenant,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,REPEAT('a',64),4,'text/plain')",
            tenant,
            fileId,
            "test-source-" + java.util.UUID.randomUUID()
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            tenant,
            fileId
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'context-file-check',?)",
            tenant,
            caseId,
            version,
            user
        );
        long taskId = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND idempotency_key='context-file-check'",
            Long.class,
            tenant
        );
        assertNotNull(taskContexts.get(taskId));
        String objectKey = db.queryForObject(
            "SELECT object_key FROM file_version WHERE id=?",
            String.class,
            version
        );
        var temporary = java.nio.file.Files.createTempFile("lexflow-task-source-", ".txt");
        try {
            java.nio.file.Files.write(temporary, new byte[] { 1, 2, 3, 4 });
            storage.put(objectKey, temporary, "application/octet-stream");
            String source = "/internal/v1/tasks/" + taskId + "/source";
            perform(get(source).param("attempt", "0").param("stateVersion", "1")).andExpect(
                status().isUnauthorized()
            );
            perform(
                get(source)
                    .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                    .param("attempt", "1")
                    .param("stateVersion", "1")
            ).andExpect(status().isConflict());
            perform(
                get(source)
                    .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                    .param("attempt", "0")
                    .param("stateVersion", "1")
            )
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(new byte[] { 1, 2, 3, 4 }));
        } finally {
            storage.remove(objectKey);
            java.nio.file.Files.deleteIfExists(temporary);
        }

        db.update(
            "UPDATE tenant_user SET status='DISABLED' WHERE tenant_id=? AND user_id=?",
            tenant,
            user
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            taskContexts.get(taskId)
        );
        db.update(
            "UPDATE tenant_user SET status='ACTIVE' WHERE tenant_id=? AND user_id=?",
            tenant,
            user
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            taskContexts.get(taskId)
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'file:read')",
            tenant,
            tenant
        );
        assertNotNull(taskContexts.get(taskId));
        assertEquals(1, asyncTasks.countAvailableSource(tenant, caseId, version));
        assertEquals(0, asyncTasks.countAvailableSource(tenant, otherCase, version));
        assertEquals(0, asyncTasks.countAvailableSource(other, caseId, version));
        for (String status : java.util.List.of("UPLOADING", "FAILED", "DELETED")) {
            db.update("UPDATE file_object SET status=? WHERE id=?", status, fileId);
            assertEquals(0, asyncTasks.countAvailableSource(tenant, caseId, version));
            assertNull(asyncTasks.getTaskContext(taskId));
            perform(
                get("/internal/v1/tasks/" + taskId + "/source")
                    .header("X-Internal-Secret", "dev-internal-secret-change-in-prod")
                    .param("attempt", "0")
                    .param("stateVersion", "1")
            ).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound()
            );
        }
    }

    // ─── 原有集成测试 ────────────────────────────────────────────────────────────

    @Test
    void loginCreateCaseOwnerAndLogout() throws Exception {
        var s = login();
        var created = perform(
            post("/api/v1/cases")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(
                    "{\"caseNo\":\"TEST-001\",\"title\":\"Integration case\",\"type\":\"CIVIL\"}"
                )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ownerId").value(Long.toString(user)))
            .andReturn();
        String id = json
            .readTree(created.getResponse().getContentAsString())
            .at("/data/id")
            .asText();
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=? AND member_role='OWNER'",
                Integer.class,
                tenant,
                id,
                user
            )
        );
        perform(
            delete("/api/v1/cases/" + id + "/members/" + user)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
        ).andExpect(status().isBadRequest());
        perform(
            post("/api/v1/auth/logout").cookie(s.value()).header("X-CSRF-TOKEN", s.token())
        ).andExpect(status().isOk());
        perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfAndRevocationAreEnforced() throws Exception {
        var s = login();
        perform(
            post("/api/v1/cases").cookie(s.value()).contentType("application/json").content("{}")
        ).andExpect(status().isForbidden());
        db.update(
            "UPDATE tenant_user SET status='DISABLED' WHERE tenant_id=? AND user_id=?",
            tenant,
            user
        );
        perform(get("/api/v1/auth/me").cookie(s.value())).andExpect(status().isUnauthorized());
    }

    @Test
    void cannotLoginToUnrelatedTenant() throws Exception {
        var s = csrf(null);
        perform(
            post("/api/v1/auth/login")
                .with(r -> {
                    r.setRemoteAddr(testAddress);
                    return r;
                })
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(
                    "{\"username\":\"integration-only\",\"password\":\"Test-only-password!\",\"tenantId\":" +
                        other +
                        "}"
                )
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void databaseRejectsCrossTenantCaseOwner() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
            db.update(
                "INSERT INTO case_info(tenant_id,case_no,title,case_type,owner_id) VALUES(?,'INVALID','Invalid','CIVIL',?)",
                other,
                user
            )
        );
    }

    @Test
    void lastAdminIsProtectedAndCrossTenantUpdatesFail() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        assertThrows(IllegalArgumentException.class, () ->
            organizations.changeMembership(actor, user, "DISABLED", "TENANT_ADMIN")
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            organizations.changeMembership(actor, other, "DISABLED", "TENANT_ADMIN")
        );
    }

    @Test
    void createsHashedIdentityWithoutGrantingOtherTenantMembership() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        String id = organizations.createUser(
            actor,
            "created-test-user",
            "A-test-only-password!",
            "New User",
            "TENANT_ADMIN"
        );
        String hash = db.queryForObject(
            "SELECT password_hash FROM sys_user WHERE id=?",
            String.class,
            id
        );
        assertTrue(new BCryptPasswordEncoder().matches("A-test-only-password!", hash));
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM tenant_user WHERE user_id=? AND tenant_id=?",
                Integer.class,
                id,
                other
            )
        );
    }

    @Test
    void bootstrapRefusesExistingTenantData() {
        assertThrows(IllegalStateException.class, () ->
            organizations.initialize("unexpected", "unexpected", "Valid-test-password!")
        );
    }

    @Test
    void ownerTransferIsAtomicAndRejectsStaleVersion() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        String successor = organizations.createUser(
            actor,
            "successor-test",
            "A-test-only-password!",
            "Successor",
            "TENANT_ADMIN"
        );
        var created = cases.create(actor, "TRANSFER-1", "Transfer", "CIVIL", "");
        var moved = cases.transferOwner(
            actor,
            Long.parseLong(created.id()),
            Long.parseLong(successor),
            created.version()
        );
        assertEquals(successor, moved.ownerId());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM case_member WHERE tenant_id=? AND case_id=? AND member_role='OWNER'",
                Integer.class,
                tenant,
                created.id()
            )
        );
        assertEquals(
            "EDITOR",
            db.queryForObject(
                "SELECT member_role FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
                String.class,
                tenant,
                created.id(),
                user
            )
        );
        assertThrows(java.util.ConcurrentModificationException.class, () ->
            cases.transferOwner(actor, Long.parseLong(created.id()), user, created.version())
        );
    }

    @Test
    void filtersAreCombinedAndReturnPagination() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        cases.create(actor, "FILTER-1", "Literal 100%", "CIVIL", "");
        cases.create(actor, "FILTER-2", "Other", "CRIMINAL", "");
        var page = cases.list(actor, 1, 20, "100%", "DRAFT", "CIVIL", user);
        assertEquals(1, page.total());
        assertEquals("FILTER-1", page.items().getFirst().caseNo());
        assertEquals(0, cases.list(actor, 1, 20, "", "ACTIVE", null, null).total());
    }

    // ─── 文件上传 / 下载 / 删除 集成测试 ────────────────────────────────────────

    /** 最小 1×1 PNG（真实文件签名），用于集成上传测试 */
    private static final byte[] TINY_PNG = {
        (byte) 0x89,
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x02,
        0x00,
        0x00,
        0x00,
        (byte) 0x90,
        0x77,
        0x53,
        (byte) 0xDE,
        0x00,
        0x00,
        0x00,
        0x0C,
        0x49,
        0x44,
        0x41,
        0x54,
        0x08,
        (byte) 0xD7,
        0x63,
        (byte) 0xF8,
        (byte) 0xCF,
        (byte) 0xC0,
        0x00,
        0x00,
        0x00,
        0x02,
        0x00,
        0x01,
        (byte) 0xE2,
        0x21,
        (byte) 0xBC,
        0x33,
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        (byte) 0xAE,
        0x42,
        0x60,
        (byte) 0x82,
    };

    /** 正常上传 → 列表可见 → 下载成功 → 删除后下载失败 */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    @Test
    void uploadDownloadDeleteLifecycle() throws Exception {
        var s = login();
        // 1. 创建案件
        var caseResult = perform(
            post("/api/v1/cases")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content("{\"caseNo\":\"FILE-001\",\"title\":\"File Test\",\"type\":\"CIVIL\"}")
        )
            .andExpect(status().isOk())
            .andReturn();
        String caseId = json
            .readTree(caseResult.getResponse().getContentAsString())
            .at("/data/id")
            .asText();

        // 2. 上传 PNG 文件
        var pngFile = new MockMultipartFile("file", "evidence.png", "image/png", TINY_PNG);
        var uploadResult = perform(
            multipart("/api/v1/cases/" + caseId + "/files")
                .file(pngFile)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.data.displayName").value("evidence.png"))
            .andExpect(jsonPath("$.data.sha256").isNotEmpty())
            .andReturn();
        String fileId = json
            .readTree(uploadResult.getResponse().getContentAsString())
            .at("/data/id")
            .asText();

        // 3. 列表中可见
        perform(get("/api/v1/cases/" + caseId + "/files").cookie(s.value()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(fileId));

        // 4. 下载成功
        perform(get("/api/v1/files/" + fileId + "/download").cookie(s.value()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"));

        // 5. 删除后下载失败（403）
        perform(
            delete("/api/v1/files/" + fileId)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
        ).andExpect(status().isOk());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_cleanup_job WHERE tenant_id=? AND file_id=? AND status='PENDING'",
                Integer.class,
                tenant,
                fileId
            )
        );
        perform(get("/api/v1/files/" + fileId + "/download").cookie(s.value())).andExpect(
            status().isForbidden()
        );
    }

    /** 归档案件拒绝上传（400） */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    @Test
    void archivedCaseRejectsUpload() throws Exception {
        var s = login();
        var caseResult = perform(
            post("/api/v1/cases")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content("{\"caseNo\":\"FILE-ARCH\",\"title\":\"Archive File\",\"type\":\"CIVIL\"}")
        )
            .andExpect(status().isOk())
            .andReturn();
        var caseNode = json.readTree(caseResult.getResponse().getContentAsString());
        String caseId = caseNode.at("/data/id").asText();
        long version = caseNode.at("/data/version").asLong();

        // 先激活，再按文档状态机归档。
        perform(
            patch("/api/v1/cases/" + caseId)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(
                    "{\"version\":" +
                        version +
                        ",\"title\":\"Archive File\",\"description\":\"\",\"status\":\"ACTIVE\"}"
                )
        ).andExpect(status().isOk());
        version++;
        // 归档
        perform(
            patch("/api/v1/cases/" + caseId)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content(
                    "{\"version\":" +
                        version +
                        ",\"title\":\"Archive File\",\"description\":\"\",\"status\":\"ARCHIVED\"}"
                )
        ).andExpect(status().isOk());

        // 归档后上传应返回 403
        var pngFile = new MockMultipartFile("file", "evidence.png", "image/png", TINY_PNG);
        perform(
            multipart("/api/v1/cases/" + caseId + "/files")
                .file(pngFile)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
        ).andExpect(status().isForbidden());
    }

    /** 非案件成员不能下载文件（AccessDenied） */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    @Test
    void nonMemberCannotDownloadFile() throws Exception {
        var s = login();
        // 1. 创建案件并上传文件
        var caseResult = perform(
            post("/api/v1/cases")
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
                .contentType("application/json")
                .content("{\"caseNo\":\"FILE-PRIV\",\"title\":\"Private File\",\"type\":\"CIVIL\"}")
        )
            .andExpect(status().isOk())
            .andReturn();
        String caseId = json
            .readTree(caseResult.getResponse().getContentAsString())
            .at("/data/id")
            .asText();

        var pngFile = new MockMultipartFile("file", "secret.png", "image/png", TINY_PNG);
        var uploadResult = perform(
            multipart("/api/v1/cases/" + caseId + "/files")
                .file(pngFile)
                .cookie(s.value())
                .header("X-CSRF-TOKEN", s.token())
        )
            .andExpect(status().isOk())
            .andReturn();
        String fileId = json
            .readTree(uploadResult.getResponse().getContentAsString())
            .at("/data/id")
            .asText();

        // 2. 创建第二个用户（同租户，但未加入该案件）
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        db.update(
            "INSERT INTO sys_role(tenant_id,code,name) VALUES(?,'LAWYER','Test Lawyer')",
            tenant
        );
        String otherId = organizations.createUser(
            actor,
            "non-member-file",
            "A-test-only-password!",
            "Non Member",
            "LAWYER"
        );

        // 3. 确认非成员没有 case_member 记录
        assertEquals(
            0L,
            db.queryForObject(
                "SELECT COUNT(*) FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
                Long.class,
                tenant,
                caseId,
                Long.parseLong(otherId)
            )
        );

        // 4. 非成员直接调用 FileService.download() → 应抛 AccessDenied
        var nonMember = new com.lexflow.service.identity.Identity(
            Long.parseLong(otherId),
            tenant,
            0,
            "Non Member"
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            fileService.download(nonMember, Long.parseLong(fileId))
        );
    }

    @Test
    void fileReadRequiresExplicitPermissionEvenForOwner() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        var c = cases.create(actor, "FILE-RBAC", "RBAC check", "CIVIL", "");
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
            tenant
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            fileService.listFiles(actor, Long.parseLong(c.id()))
        );
    }

    @Test
    void tenantAdminMayReadFilesWithoutCaseMembership() {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        var c = cases.create(actor, "FILE-ADMIN", "Admin check", "CIVIL", "");
        db.update("DELETE FROM case_member WHERE tenant_id=? AND case_id=?", tenant, c.id());
        assertTrue(fileService.listFiles(actor, Long.parseLong(c.id())).isEmpty());
    }

    @org.junit.jupiter.api.AfterEach
    void cleanCommittedFileFixtures() throws Exception {
        org.mockito.Mockito.reset(storage);
        if (
            org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
        ) return;
        var keys = db.queryForList(
            "SELECT object_key FROM file_version WHERE tenant_id=?",
            String.class,
            tenant
        );
        for (String key : keys) storage.remove(key);
        var ids = db.queryForList(
            "SELECT user_id FROM tenant_user WHERE tenant_id=?",
            Long.class,
            tenant
        );
        for (String table : java.util.List.of(
            "file_cleanup_job",
            "file_version",
            "file_object",
            "case_member",
            "case_info",
            "audit_log",
            "role_permission",
            "user_role",
            "sys_role",
            "tenant_user"
        ))
            db.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
        for (Long id : ids) db.update("DELETE FROM sys_user WHERE id=?", id);
        db.update("DELETE FROM tenant WHERE id IN (?,?)", tenant, other);
    }

    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    @Test
    void failedStoragePersistsFailedMetadataAndCleanup() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        var c = cases.create(actor, "FAIL-UPLOAD", "Failure", "CIVIL", "");
        var temp = java.nio.file.Files.createTempFile("lexflow-failure-test-", ".txt");
        java.nio.file.Files.writeString(temp, "test content");
        org.mockito.Mockito.doThrow(new java.io.IOException("private storage error"))
            .when(storage)
            .put(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
            );
        try {
            var error = assertThrows(IllegalStateException.class, () ->
                fileService.upload(actor, Long.parseLong(c.id()), temp, "test.txt", "text/plain")
            );
            assertFalse(error.getMessage().contains("private storage error"));
            String id = db.queryForObject(
                "SELECT CAST(id AS CHAR) FROM file_object WHERE tenant_id=? AND case_id=?",
                String.class,
                tenant,
                c.id()
            );
            assertEquals(
                "FAILED",
                db.queryForObject("SELECT status FROM file_object WHERE id=?", String.class, id)
            );
            assertEquals(
                1,
                db.queryForObject(
                    "SELECT COUNT(*) FROM file_cleanup_job WHERE tenant_id=? AND file_id=? AND status='PENDING'",
                    Integer.class,
                    tenant,
                    id
                )
            );
            assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
                fileService.download(actor, Long.parseLong(id))
            );
            org.mockito.Mockito.reset(storage);
            assertTrue(fileCleanup.processOne());
            assertEquals(
                "DONE",
                db.queryForObject(
                    "SELECT status FROM file_cleanup_job WHERE tenant_id=? AND file_id=?",
                    String.class,
                    tenant,
                    id
                )
            );
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    @Test
    void interruptedUploadExpiresAndCannotBePublishedLate() throws Exception {
        var actor = new com.lexflow.service.identity.Identity(user, tenant, 0, "Test User");
        var c = cases.create(actor, "INTERRUPTED-UPLOAD", "Interrupted", "CIVIL", "");
        var path = java.nio.file.Files.createTempFile("lexflow-interruption-", ".txt");
        try {
            java.nio.file.Files.writeString(path, "temporary evidence");
            var meta = new com.lexflow.service.file.UploadInspector().inspect(
                path,
                "evidence.txt",
                "text/plain"
            );
            var pending = uploadMetadata.begin(actor, Long.parseLong(c.id()), meta);
            storage.put(pending.key(), path, meta.contentType());
            db.update(
                "UPDATE file_cleanup_job SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND file_id=?",
                tenant,
                pending.fileId()
            );
            assertTrue(fileCleanup.processOne());
            assertEquals(
                "FAILED",
                db.queryForObject(
                    "SELECT status FROM file_object WHERE tenant_id=? AND id=?",
                    String.class,
                    tenant,
                    pending.fileId()
                )
            );
            assertThrows(IllegalStateException.class, () ->
                uploadMetadata.complete(actor, Long.parseLong(c.id()), pending.fileId())
            );
            assertThrows(io.minio.errors.ErrorResponseException.class, () -> {
                try (var in = storage.open(pending.key())) {
                    in.read();
                }
            });
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
    }
}
