package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.service.CaseService;
import com.lexflow.service.IdentityService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
import org.springframework.session.SessionRepository;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Real read-permission and tenant-bound owner filtering, with owned fixtures and no external calls. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class CaseOwnerFilterIntegrationTest {

    static final long TENANT = 910000245L,
        OTHER = 910000246L;
    static final long ADMIN = 910000345L,
        READER = 910000346L,
        VISIBLE = 910000347L,
        HIDDEN = 910000348L,
        FOREIGN = 910000349L;
    static final String PASSWORD = "Owner-filter-test-only-2026!",
        HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    @Autowired
    JdbcTemplate db;

    @Autowired
    CaseService cases;

    @Autowired
    IdentityService identities;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    SessionRepository<?> sessions;

    final Identity reader = new Identity(READER, TENANT, 0, "Reader fixture"),
        admin = new Identity(ADMIN, TENANT, 0, "Admin fixture");
    final Identity outsider = new Identity(FOREIGN, OTHER, 0, "Foreign fixture");
    final Set<String> sessionIds = new LinkedHashSet<>(),
        traces = new LinkedHashSet<>();
    String address;
    long visibleCase;

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
                "SELECT COUNT(*) FROM sys_user WHERE id IN (?,?,?,?,?)",
                Integer.class,
                ADMIN,
                READER,
                VISIBLE,
                HIDDEN,
                FOREIGN
            )
        );
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'owner-filter-only'),(?,'owner-filter-other-only')",
            TENANT,
            OTHER
        );
        for (long user : List.of(ADMIN, READER, VISIBLE, HIDDEN, FOREIGN)) {
            long tenant = user == FOREIGN ? OTHER : TENANT;
            String code =
                user == ADMIN || user == FOREIGN ? "TENANT_ADMIN" : "OWNER_FILTER_" + user;
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",
                user,
                "owner-filter-only-" + user,
                HASH,
                "Owner " + user
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Owner filter fixture')",
                user,
                tenant,
                code
            );
            db.update(
                "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                tenant,
                user,
                user
            );
            db.update(
                "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'case:read')",
                tenant,
                user
            );
        }
        visibleCase = source(TENANT, VISIBLE, "OWNER-VISIBLE");
        source(TENANT, VISIBLE, "OWNER-DEDUP");
        source(TENANT, HIDDEN, "OWNER-HIDDEN");
        source(OTHER, FOREIGN, "OWNER-FOREIGN");
        address = "fd00::" + UUID.randomUUID().toString().substring(0, 8);
    }

    long source(long tenant, long owner, String number) {
        db.update(
            "INSERT INTO case_info(tenant_id,case_no,title,case_type,owner_id) VALUES(?,?,'Owner filter synthetic','CIVIL',?)",
            tenant,
            number,
            owner
        );
        long id = db.queryForObject(
            "SELECT id FROM case_info WHERE tenant_id=? AND case_no=?",
            Long.class,
            tenant,
            number
        );
        db.update(
            "INSERT INTO case_member(tenant_id,case_id,user_id,member_role) VALUES(?,?,?,'OWNER')",
            tenant,
            id,
            owner
        );
        if (tenant == TENANT && owner == VISIBLE) db.update(
            "INSERT INTO case_member(tenant_id,case_id,user_id,member_role) VALUES(?,?,?,'READER')",
            tenant,
            id,
            READER
        );
        return id;
    }

    @AfterEach
    void cleanupSessions() {
        for (String id : sessionIds) {
            sessions.deleteById(id);
            assertNull(sessions.findById(id));
        }
        if (address != null) redis.delete("lexflow:login:ip:" + address);
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

    ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andDo(result -> {
            String trace = result.getResponse().getHeader("X-Trace-Id");
            if (trace != null) traces.add(trace);
            for (String header : result.getResponse().getHeaders("Set-Cookie")) {
                var cookie = MockCookie.parse(header);
                if (
                    "SESSION".equals(cookie.getName()) && !cookie.getValue().isBlank()
                ) sessionIds.add(
                    new String(
                        Base64.getDecoder().decode(cookie.getValue()),
                        StandardCharsets.UTF_8
                    )
                );
            }
        });
    }

    MockCookie login() throws Exception {
        var first = perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var cookie = MockCookie.parse(first.getResponse().getHeader("Set-Cookie"));
        String token = json
            .readTree(first.getResponse().getContentAsString())
            .at("/data/token")
            .asText();
        var response = perform(
            post("/api/v1/auth/login")
                .cookie(cookie)
                .header("X-CSRF-TOKEN", token)
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "owner-filter-only-" + READER,
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
        return MockCookie.parse(response.getResponse().getHeader("Set-Cookie"));
    }

    @Test
    void readerNeedsNoMemberPermissionAndReceivesOnlyVisibleOwnersAndMatchingCases() {
        assertFalse(identities.hasPermission(reader, "case:member"));
        var owners = cases.owners(reader);
        assertEquals(
            List.of(Map.of("id", Long.toString(VISIBLE), "name", "Owner " + VISIBLE)),
            owners
        );
        assertEquals(2, cases.list(reader, 1, 20, null, null, null, VISIBLE).total());
        assertEquals(0, cases.list(reader, 1, 20, null, null, null, HIDDEN).total());
        assertEquals(0, cases.list(reader, 1, 20, null, null, null, FOREIGN).total());
    }

    @Test
    void adminCanFilterAllOwnersInOwnTenantAndNeverForeignAccounts() {
        assertEquals(
            Set.of(Long.toString(VISIBLE), Long.toString(HIDDEN)),
            cases
                .owners(admin)
                .stream()
                .map(row -> row.get("id"))
                .collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(
            List.of(Map.of("id", Long.toString(FOREIGN), "name", "Owner " + FOREIGN)),
            cases.owners(outsider)
        );
        assertEquals(1, cases.list(admin, 1, 20, null, null, null, HIDDEN).total());
    }

    @Test
    void historicalArchivedCasesRemainFilterableWhenTheirOwnerIsDisabled() {
        db.update(
            "UPDATE case_info SET status='ARCHIVED' WHERE tenant_id=? AND id=?",
            TENANT,
            visibleCase
        );
        db.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", VISIBLE);
        db.update(
            "UPDATE tenant_user SET status='DISABLED' WHERE tenant_id=? AND user_id=?",
            TENANT,
            VISIBLE
        );
        assertEquals(Long.toString(VISIBLE), cases.owners(reader).getFirst().get("id"));
        assertEquals(1, cases.list(reader, 1, 20, null, "ARCHIVED", null, VISIBLE).total());
    }

    @Test
    void membershipWithdrawalPermissionWithdrawalAndInactiveIdentityCannotEnumerate() {
        db.update("DELETE FROM case_member WHERE tenant_id=? AND user_id=?", TENANT, READER);
        assertTrue(cases.owners(reader).isEmpty());
        db.update("DELETE FROM role_permission WHERE tenant_id=? AND role_id=?", TENANT, READER);
        assertThrows(AccessDenied.class, () -> cases.owners(reader));
        db.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", ADMIN);
        assertThrows(AccessDenied.class, () -> cases.owners(admin));
    }

    @Test
    void realReadOnlyHttpRouteReturnsStringIdsAndRejectsRevokedPermission() throws Exception {
        var cookie = login();
        perform(get("/api/v1/cases/owners").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(Long.toString(VISIBLE)))
            .andExpect(jsonPath("$.data[0].name").value("Owner " + VISIBLE))
            .andExpect(jsonPath("$.data[0].username").doesNotExist());
        perform(get("/api/v1/cases").cookie(cookie).param("ownerId", Long.toString(HIDDEN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
        db.update("DELETE FROM role_permission WHERE tenant_id=? AND role_id=?", TENANT, READER);
        perform(get("/api/v1/cases/owners").cookie(cookie)).andExpect(status().isForbidden());
    }

    @Test
    void memberListUsesCurrentSchemaAndRetainsReadAuthorization() throws Exception {
        // case_member has no joined_at column: exercise the real mapper against Flyway's schema.
        assertEquals(
            List.of(Long.toString(READER), Long.toString(VISIBLE)),
            cases
                .members(reader, visibleCase)
                .stream()
                .map(row -> row.get("id"))
                .toList()
        );
        assertThrows(AccessDenied.class, () -> cases.members(outsider, visibleCase));
        var cookie = login();
        perform(get("/api/v1/cases/" + visibleCase + "/members").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].id").value(Long.toString(READER)))
            .andExpect(jsonPath("$.data[0].role").value("READER"))
            .andExpect(jsonPath("$.data[1].role").value("OWNER"))
            .andExpect(jsonPath("$.data[0].username").doesNotExist());
        db.update(
            "DELETE FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
            TENANT,
            visibleCase,
            READER
        );
        perform(get("/api/v1/cases/" + visibleCase + "/members").cookie(cookie)).andExpect(
            status().isForbidden()
        );
    }
}
