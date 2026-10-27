package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.CaseService;
import com.lexflow.service.identity.Identity;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Committed, owned local fixtures prove that independent audits survive business rollback. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({
    RequestAuditIntegrationTest.Failures.class,
    RequestAuditIntegrationTest.EarlyFailure.class,
})
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class RequestAuditIntegrationTest {

    static final long TENANT = 910000150L,
        MEMBER = 910000151L,
        OTHER = 910000152L,
        READER_ROLE = 910000153L;
    static final String PASSWORD = "Audit-fixture-password-2026!";
    static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    @Autowired
    JdbcTemplate db;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    CaseService cases;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    final Set<String> traces = new HashSet<>();
    Cookie cookie;
    String csrf, address, loginTrace;
    boolean ownedFixture;

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
                "SELECT COUNT(*) FROM sys_user WHERE id IN (?,?,?)",
                Integer.class,
                TENANT,
                MEMBER,
                OTHER
            )
        );
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'request-audit-integration-only'),(?,'request-audit-other-only')",
            TENANT,
            OTHER
        );
        ownedFixture = true;
        for (long user : List.of(TENANT, MEMBER, OTHER)) {
            long tenant = user == OTHER ? OTHER : TENANT;
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,?,'Audit synthetic fixture')",
                user,
                "audit-fixture-" + user,
                HASH
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Audit test role')",
                user,
                tenant,
                user == MEMBER ? "LAWYER" : "TENANT_ADMIN"
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
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'READER','Audit reader role')",
            READER_ROLE,
            TENANT
        );
        for (String permission : List.of("case:read", "file:read", "document:read", "task:read"))
            db.update(
                "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,?)",
                TENANT,
                READER_ROLE,
                permission
            );
        address = "fd00::" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanOwnedFixtures() throws Exception {
        if (!ownedFixture) return;
        if (cookie != null && csrf != null) request(
            post("/api/v1/auth/logout").header("X-CSRF-TOKEN", csrf)
        );
        redis.delete("lexflow:login:ip:" + address);
        for (String trace : traces) db.update("DELETE FROM audit_log WHERE trace_id=?", trace);
        var users = db.queryForList(
            "SELECT user_id FROM tenant_user WHERE tenant_id IN (?,?)",
            Long.class,
            TENANT,
            OTHER
        );
        for (String table : List.of(
            "outbox_event",
            "async_task",
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
            db.update("DELETE FROM " + table + " WHERE tenant_id IN (?,?)", TENANT, OTHER);
        for (long user : users) db.update("DELETE FROM sys_user WHERE id=?", user);
        db.update("DELETE FROM tenant WHERE id IN (?,?)", TENANT, OTHER);
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id IN (?,?)",
                Integer.class,
                TENANT,
                OTHER
            )
        );
    }

    MvcResult request(MockHttpServletRequestBuilder builder) throws Exception {
        if (cookie != null) builder.cookie(cookie);
        var result = mvc
            .perform(
                builder.with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })
            )
            .andReturn();
        String trace = result.getResponse().getHeader("X-Trace-Id");
        assertNotNull(trace);
        UUID.fromString(trace);
        traces.add(trace);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(trace, body.path("traceId").asText());
        int httpStatus = result.getResponse().getStatus();
        assertEquals(
            httpStatus >= 200 && httpStatus < 300 ? 200 : httpStatus,
            body.path("code").asInt()
        );
        return result;
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Object value)
        throws Exception {
        return builder
            .header("X-CSRF-TOKEN", csrf)
            .contentType("application/json")
            .content(json.writeValueAsString(value));
    }

    void csrf() throws Exception {
        var result = request(get("/api/v1/auth/csrf"));
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        if (setCookie != null) cookie = MockCookie.parse(setCookie);
        csrf = body(result).at("/data/token").asText();
    }

    void login(long user) throws Exception {
        csrf();
        var result = request(
            write(
                post("/api/v1/auth/login"),
                Map.of(
                    "username",
                    "audit-fixture-" + user,
                    "password",
                    PASSWORD,
                    "tenantId",
                    user == OTHER ? OTHER : TENANT
                )
            )
        );
        assertEquals(200, result.getResponse().getStatus());
        loginTrace = result.getResponse().getHeader("X-Trace-Id");
        cookie = MockCookie.parse(result.getResponse().getHeader("Set-Cookie"));
        csrf();
    }

    long createCase(String suffix) throws Exception {
        var result = request(
            write(
                post("/api/v1/cases"),
                Map.of(
                    "caseNo",
                    "AUDIT-" + suffix,
                    "title",
                    "Audit fixture case",
                    "type",
                    "CIVIL",
                    "description",
                    ""
                )
            )
        );
        assertEquals(200, result.getResponse().getStatus());
        return body(result).at("/data/id").asLong();
    }

    Map<String, Object> audit(String trace, String action) {
        return db.queryForMap(
            "SELECT tenant_id,actor_id,action,result,detail_json,trace_id,object_id FROM audit_log WHERE trace_id=? AND action=?",
            trace,
            action
        );
    }

    JsonNode detail(MvcResult result, String action) throws Exception {
        return json.readTree(
            audit(result.getResponse().getHeader("X-Trace-Id"), action)
                .get("detail_json")
                .toString()
        );
    }

    @Test
    void loginLogoutAndBusinessAuditShareTheServerTraceAndIgnoreSpoofedHeader() throws Exception {
        login(TENANT);
        var login = audit(loginTrace, "auth:login");
        assertEquals(TENANT, ((Number) login.get("tenant_id")).longValue());
        assertEquals(TENANT, ((Number) login.get("actor_id")).longValue());
        assertEquals("SUCCESS", login.get("result"));
        var created = request(
            write(
                post("/api/v1/cases"),
                Map.of(
                    "caseNo",
                    "AUDIT-TRACE",
                    "title",
                    "Synthetic",
                    "type",
                    "CIVIL",
                    "description",
                    ""
                )
            ).header("X-Trace-Id", "attacker-controlled-trace")
        );
        assertEquals(200, created.getResponse().getStatus());
        String trace = created.getResponse().getHeader("X-Trace-Id");
        assertNotEquals("attacker-controlled-trace", trace);
        assertNotEquals(loginTrace, trace);
        assertEquals(
            body(created).at("/data/id").asText(),
            audit(trace, "case:create").get("object_id")
        );
        var loggedOut = request(post("/api/v1/auth/logout").header("X-CSRF-TOKEN", csrf));
        assertEquals(200, loggedOut.getResponse().getStatus());
        assertEquals(
            "SUCCESS",
            audit(loggedOut.getResponse().getHeader("X-Trace-Id"), "auth:logout").get("result")
        );
        assertEquals(401, request(get("/api/v1/auth/me")).getResponse().getStatus());
        cookie = null;
    }

    @Test
    void failedAnonymousLoginDoesNotTrustClaimedTenantOrStoreInputSecrets() throws Exception {
        csrf();
        String secret = "fake-secret-must-not-be-audited";
        var failed = request(
            write(
                post("/api/v1/auth/login").queryParam("private", secret),
                Map.of(
                    "username",
                    "audit-fixture-" + TENANT,
                    "password",
                    secret,
                    "tenantId",
                    TENANT
                )
            ).header("Cookie-Note", secret)
        );
        assertEquals(401, failed.getResponse().getStatus());
        var row = audit(failed.getResponse().getHeader("X-Trace-Id"), "auth:login");
        assertNull(row.get("tenant_id"));
        assertNull(row.get("actor_id"));
        assertEquals("DENIED", row.get("result"));
        String stored = row.get("detail_json").toString();
        assertFalse(stored.contains(secret));
        assertFalse(stored.contains("audit-fixture-"));
        assertEquals("/api/v1/auth/login", json.readTree(stored).path("route").asText());
    }

    @Test
    void csrfInternalAuthenticationAndRevokedSessionFailuresHaveTraceAndPersistAudit()
        throws Exception {
        login(TENANT);
        var csrfDenied = request(
            post("/api/v1/cases").contentType("application/json").content("{}")
        );
        assertEquals(403, csrfDenied.getResponse().getStatus());
        assertEquals(
            TENANT,
            (
                (Number) audit(
                    csrfDenied.getResponse().getHeader("X-Trace-Id"),
                    "request:failure"
                ).get("actor_id")
            ).longValue()
        );
        var internal = request(get("/internal/v1/tasks/1/context"));
        assertEquals(401, internal.getResponse().getStatus());
        audit(internal.getResponse().getHeader("X-Trace-Id"), "request:failure");
        db.update(
            "UPDATE tenant_user SET status='DISABLED' WHERE tenant_id=? AND user_id=?",
            TENANT,
            TENANT
        );
        var expired = request(get("/api/v1/cases"));
        assertEquals(401, expired.getResponse().getStatus());
        assertEquals(
            TENANT,
            (
                (Number) audit(
                    expired.getResponse().getHeader("X-Trace-Id"),
                    "request:failure"
                ).get("actor_id")
            ).longValue()
        );
    }

    @Test
    void failedBusinessWriteRollsBackButItsIndependentAuditRemains() throws Exception {
        login(TENANT);
        var failed = request(post("/api/v1/test-audit/rollback").header("X-CSRF-TOKEN", csrf));
        assertEquals(409, failed.getResponse().getStatus());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM case_info WHERE tenant_id=?",
                Integer.class,
                TENANT
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='case:create'",
                Integer.class,
                TENANT
            )
        );
        var row = audit(failed.getResponse().getHeader("X-Trace-Id"), "request:failure");
        assertEquals("FAILED", row.get("result"));
        assertEquals(
            409,
            json.readTree(row.get("detail_json").toString()).path("httpStatus").asInt()
        );
        // Repeat inside an outer rollback-only transaction, as used by the other integration fixtures.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try {
                var again = request(
                    post("/api/v1/test-audit/rollback").header("X-CSRF-TOKEN", csrf)
                );
                assertEquals(409, again.getResponse().getStatus());
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
            tx.setRollbackOnly();
        });
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='request:failure'",
                Integer.class,
                TENANT
            )
        );
    }

    @Test
    void organizationChangesStoreExactRolesEffectivePermissionsAndStatusBeforeAndAfter()
        throws Exception {
        login(TENANT);
        var changed = request(
            write(
                patch("/api/v1/organization/users/" + MEMBER),
                Map.of("status", "ACTIVE", "role", "READER")
            )
        );
        assertEquals(200, changed.getResponse().getStatus());
        var state = detail(changed, "organization:membership:update");
        assertEquals("LAWYER", state.at("/before/roles/0").asText());
        assertEquals("READER", state.at("/after/roles/0").asText());
        assertTrue(state.path("before").path("permissions").toString().contains("case:update"));
        assertFalse(state.path("after").path("permissions").toString().contains("case:update"));
        var disabled = request(
            write(
                patch("/api/v1/organization/users/" + MEMBER),
                Map.of("status", "DISABLED", "role", "READER")
            )
        );
        var disabledState = detail(disabled, "organization:membership:update");
        assertEquals("ACTIVE", disabledState.at("/before/status").asText());
        assertEquals("DISABLED", disabledState.at("/after/status").asText());
        assertEquals(0, disabledState.at("/after/permissions").size());
        var created = request(
            write(
                post("/api/v1/organization/users"),
                Map.of(
                    "username",
                    "audit-new-owned-user",
                    "password",
                    PASSWORD,
                    "name",
                    "Synthetic",
                    "role",
                    "LAWYER"
                )
            )
        );
        assertEquals(200, created.getResponse().getStatus());
        var createState = detail(created, "organization:user:create");
        assertTrue(createState.path("before").isNull());
        assertFalse(createState.toString().contains(PASSWORD));
        assertFalse(createState.toString().contains(HASH));
        var lastAdmin = request(
            write(
                patch("/api/v1/organization/users/" + TENANT),
                Map.of("status", "DISABLED", "role", "READER")
            )
        );
        assertEquals(400, lastAdmin.getResponse().getStatus());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM tenant_user WHERE tenant_id=? AND user_id=? AND status='ACTIVE'",
                Integer.class,
                TENANT,
                TENANT
            )
        );
    }

    @Test
    void caseMemberChangesAndOwnerTransferRecordBothSidesAtomically() throws Exception {
        login(TENANT);
        long id = createCase("MEMBERS");
        var added = request(
            write(
                post("/api/v1/cases/" + id + "/members"),
                Map.of("userId", MEMBER, "role", "READER")
            )
        );
        var addState = detail(added, "case:member:add");
        assertTrue(addState.path("before").isNull());
        assertEquals("READER", addState.at("/after/role").asText());
        var changed = request(
            write(patch("/api/v1/cases/" + id + "/members/" + MEMBER), Map.of("role", "EDITOR"))
        );
        var changeState = detail(changed, "case:member:role");
        assertEquals("READER", changeState.at("/before/role").asText());
        assertEquals("EDITOR", changeState.at("/after/role").asText());
        var transferred = request(
            write(post("/api/v1/cases/" + id + "/owner"), Map.of("userId", MEMBER, "version", 0))
        );
        var transfer = detail(transferred, "case:owner:transfer");
        assertEquals(Long.toString(TENANT), transfer.at("/before/ownerId").asText());
        assertEquals(Long.toString(MEMBER), transfer.at("/after/ownerId").asText());
        assertEquals("OWNER", transfer.at("/before/members/0/role").asText());
        assertEquals("EDITOR", transfer.at("/after/members/0/role").asText());
        var removed = request(
            delete("/api/v1/cases/" + id + "/members/" + TENANT).header("X-CSRF-TOKEN", csrf)
        );
        var remove = detail(removed, "case:member:remove");
        assertEquals("EDITOR", remove.at("/before/role").asText());
        assertTrue(remove.path("after").isNull());
        var denied = request(
            delete("/api/v1/cases/" + id + "/members/" + MEMBER).header("X-CSRF-TOKEN", csrf)
        );
        assertEquals(400, denied.getResponse().getStatus());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM case_member WHERE tenant_id=? AND case_id=? AND member_role='OWNER'",
                Integer.class,
                TENANT,
                id
            )
        );
    }

    @Test
    void auditReadRequiresPermissionAndFiltersOrdinaryMembersAndOtherTenants() throws Exception {
        login(TENANT);
        createCase("ADMIN");
        cases.create(
            new Identity(MEMBER, TENANT, 0, "Synthetic"),
            "AUDIT-MEMBER",
            "Member case",
            "CIVIL",
            ""
        );
        cases.create(
            new Identity(OTHER, OTHER, 0, "Synthetic"),
            "AUDIT-OTHER",
            "Other case",
            "CIVIL",
            ""
        );
        login(MEMBER);
        var list = request(get("/api/v1/audit-logs").queryParam("tenantId", Long.toString(OTHER)));
        assertEquals(200, list.getResponse().getStatus());
        for (var item : body(list).at("/data/items"))
            assertEquals(Long.toString(MEMBER), item.path("actorId").asText());
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='audit:read'",
            TENANT,
            MEMBER
        );
        assertEquals(403, request(get("/api/v1/audit-logs")).getResponse().getStatus());
        login(TENANT);
        var adminList = request(get("/api/v1/audit-logs").queryParam("action", "case:create"));
        assertEquals(2, body(adminList).at("/data/total").asInt());
        assertFalse(body(adminList).toString().contains("AUDIT-OTHER"));
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='audit:read'",
            TENANT,
            TENANT
        );
        assertEquals(403, request(get("/api/v1/audit-logs")).getResponse().getStatus());
    }

    @Test
    void queuedTaskAndOutboxUseTheSubmittingRequestTraceWithoutPublishingFixtures()
        throws Exception {
        login(TENANT);
        long id = createCase("TASK");
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'audit-source.txt','AVAILABLE',?)",
            TENANT,
            id,
            TENANT
        );
        long file = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            TENANT,
            id
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,? ,?,4,'text/plain')",
            TENANT,
            file,
            "audit-fixture-not-uploaded-" + file,
            "a".repeat(64)
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            try {
                var result = request(
                    write(
                        post("/api/v1/tasks"),
                        Map.of(
                            "caseId",
                            id,
                            "type",
                            "DOCUMENT_PARSE",
                            "resourceVersionId",
                            version,
                            "idempotencyKey",
                            "audit-task-only"
                        )
                    )
                );
                assertEquals(202, result.getResponse().getStatus());
                String trace = result.getResponse().getHeader("X-Trace-Id");
                JsonNode event = json.readTree(
                    db.queryForObject(
                        "SELECT payload FROM outbox_event WHERE tenant_id=?",
                        String.class,
                        TENANT
                    )
                );
                assertEquals(trace, event.path("traceId").asText());
                audit(trace, "task:submit");
                var repeated = request(
                    write(
                        post("/api/v1/tasks"),
                        Map.of(
                            "caseId",
                            id,
                            "type",
                            "DOCUMENT_PARSE",
                            "resourceVersionId",
                            version,
                            "idempotencyKey",
                            "audit-task-only"
                        )
                    )
                );
                assertEquals(202, repeated.getResponse().getStatus());
                assertEquals(body(result).at("/data/id"), body(repeated).at("/data/id"));
                var conflict = request(
                    write(
                        post("/api/v1/tasks"),
                        Map.of(
                            "caseId",
                            id,
                            "type",
                            "DOCUMENT_PARSE",
                            "resourceVersionId",
                            version + 1,
                            "idempotencyKey",
                            "audit-task-only"
                        )
                    )
                );
                assertEquals(409, conflict.getResponse().getStatus());
                var denied = request(
                    write(
                        post("/api/v1/tasks"),
                        Map.of(
                            "caseId",
                            id,
                            "type",
                            "DOCUMENT_PARSE",
                            "resourceVersionId",
                            version + 1,
                            "idempotencyKey",
                            "audit-task-no-source"
                        )
                    )
                );
                assertEquals(403, denied.getResponse().getStatus());
                assertEquals(
                    1,
                    db.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",
                        Integer.class,
                        TENANT
                    )
                );
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
            tx.setRollbackOnly();
        });
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",
                Integer.class,
                TENANT
            )
        );
    }

    @Test
    void malformedMissingAndUnexpectedErrorsReturnSafeJsonTraceAndIndependentAudit()
        throws Exception {
        login(TENANT);
        String secret = "fake-input-must-not-enter-audit";
        var malformed = request(
            post("/api/v1/cases")
                .header("X-CSRF-TOKEN", csrf)
                .contentType("application/json")
                .content("{\"password\":\"" + secret)
        );
        assertEquals(400, malformed.getResponse().getStatus());
        var missing = request(get("/api/v1/" + secret).queryParam("password", secret));
        assertEquals(404, missing.getResponse().getStatus());
        var unexpected = request(get("/api/v1/test-audit/unexpected"));
        assertEquals(500, unexpected.getResponse().getStatus());
        for (var result : List.of(malformed, missing, unexpected)) {
            var row = audit(result.getResponse().getHeader("X-Trace-Id"), "request:failure");
            assertFalse(row.get("detail_json").toString().contains(secret));
            assertFalse(result.getResponse().getContentAsString().contains(secret));
        }
    }

    @Test
    void failureBeforeSessionAndSecurityPersistsOnceWithoutTrustingUnverifiedIdentity()
        throws Exception {
        login(TENANT);
        var result = request(
            get("/api/v1/test-audit/pre-security")
                .header("X-Tenant-Id", OTHER)
                .queryParam("tenantId", Long.toString(OTHER))
        );
        assertEquals(500, result.getResponse().getStatus());
        String trace = result.getResponse().getHeader("X-Trace-Id");
        var row = audit(trace, "request:failure");
        assertNull(row.get("tenant_id"));
        assertNull(row.get("actor_id"));
        assertEquals("FAILED", row.get("result"));
        assertEquals(
            "UNMATCHED",
            json.readTree(row.get("detail_json").toString()).path("route").asText()
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE trace_id=?",
                Integer.class,
                trace
            )
        );
    }

    @Test
    void concurrentRequestsHaveSeparateTracesAndRestoreThreadContext() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> call = () -> {
                try (var outer = TraceContext.startRequest()) {
                    String before = outer.traceId();
                    var result = mvc
                        .perform(get("/api/health").header("X-Trace-Id", "spoofed"))
                        .andReturn();
                    String trace = result.getResponse().getHeader("X-Trace-Id");
                    assertEquals(trace, body(result).path("traceId").asText());
                    assertNotEquals(before, trace);
                    assertEquals(before, TraceContext.currentOrNew());
                    assertEquals(before, org.slf4j.MDC.get("traceId"));
                    return trace;
                } finally {
                    assertNull(org.slf4j.MDC.get("traceId"));
                }
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @TestComponent
    @RestController
    static class Failures {

        private final CaseService cases;
        private final TransactionTemplate tx;

        Failures(CaseService cases, PlatformTransactionManager transactions) {
            this.cases = cases;
            this.tx = new TransactionTemplate(transactions);
        }

        @PostMapping("/api/v1/test-audit/rollback")
        void rollback(
            @org.springframework.security.core.annotation.AuthenticationPrincipal Identity actor
        ) {
            tx.executeWithoutResult(status -> {
                cases.create(actor, "AUDIT-ROLLBACK", "Synthetic rollback", "CIVIL", "");
                throw BusinessException.conflict("合成回滚验收");
            });
        }

        @GetMapping("/api/v1/test-audit/unexpected")
        void unexpected() {
            throw new RuntimeException("Synthetic audit failure only");
        }
    }

    @TestComponent
    @org.springframework.core.annotation.Order(
        org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20
    )
    static class EarlyFailure extends org.springframework.web.filter.OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            jakarta.servlet.FilterChain chain
        ) throws jakarta.servlet.ServletException, java.io.IOException {
            if (
                request.getRequestURI().equals("/api/v1/test-audit/pre-security")
            ) throw new IllegalStateException("Synthetic early filter failure");
            chain.doFilter(request, response);
        }
    }
}
