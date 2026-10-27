package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.CaseService;
import com.lexflow.service.EvidenceService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Owned synthetic fixtures roll back; no model or object-store requests. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class CaseEvidenceIntegrationTest {

    static final long TENANT = 910000130L,
        OTHER = 910000131L,
        READER = 910000132L;

    @Autowired
    JdbcTemplate db;

    @Autowired
    CaseService cases;

    @Autowired
    EvidenceService evidence;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    final Identity admin = new Identity(TENANT, TENANT, 0, "Evidence admin");
    final Identity reader = new Identity(READER, TENANT, 0, "Evidence reader");
    final Identity outsider = new Identity(OTHER, OTHER, 0, "Other admin");
    long caseId;
    Cookie cookie;
    String csrf, address;

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
            "INSERT INTO tenant(id,name) VALUES(?,'evidence-integration-only'),(?,'evidence-other-only')",
            TENANT,
            OTHER
        );
        for (long user : List.of(TENANT, OTHER, READER)) {
            assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, user)
            );
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,?,'Evidence fixture')",
                user,
                "evidence-fixture-" + user,
                user == TENANT
                    ? new BCryptPasswordEncoder(12).encode("Evidence-test-only-2026!")
                    : "unused"
            );
            long tenant = user == OTHER ? OTHER : TENANT;
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Test role')",
                user,
                tenant,
                user == READER ? "LAWYER" : "TENANT_ADMIN"
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
                .create(
                    admin,
                    "EVIDENCE-CASE",
                    "Evidence case",
                    "CIVIL",
                    "",
                    LocalDate.parse("2026-09-15")
                )
                .id()
        );
        db.update(
            "INSERT INTO case_member(tenant_id,case_id,user_id,member_role) VALUES(?,?,?,'READER')",
            TENANT,
            caseId,
            READER
        );
        address = "fd00::" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void clearOwnSession() throws Exception {
        if (cookie != null && csrf != null) mvc.perform(
            post("/api/v1/auth/logout").cookie(cookie).header("X-CSRF-TOKEN", csrf)
        ).andExpect(status().isOk());
        if (address != null) redis.delete("lexflow:login:ip:" + address);
    }

    void login() throws Exception {
        var first = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        cookie = MockCookie.parse(first.getResponse().getHeader("Set-Cookie"));
        csrf = json.readTree(first.getResponse().getContentAsString()).at("/data/token").asText();
        var result = mvc
            .perform(
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
                                "evidence-fixture-" + TENANT,
                                "password",
                                "Evidence-test-only-2026!",
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
                mvc
                    .perform(get("/api/v1/auth/csrf").cookie(cookie))
                    .andReturn()
                    .getResponse()
                    .getContentAsString()
            )
            .at("/data/token")
            .asText();
    }

    JsonNode patchCase(Map<String, Object> fields, int status) throws Exception {
        var result = mvc
            .perform(
                patch("/api/v1/cases/" + caseId)
                    .cookie(cookie)
                    .header("X-CSRF-TOKEN", csrf)
                    .contentType("application/json")
                    .content(json.writeValueAsString(fields))
            )
            .andExpect(status().is(status))
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    Map<String, Object> update(long version, String status) {
        return new LinkedHashMap<>(
            Map.of(
                "version",
                version,
                "title",
                "Updated case",
                "description",
                "Synthetic",
                "status",
                status
            )
        );
    }

    long source(long sourceCase, String name) {
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,?,'AVAILABLE',?)",
            TENANT,
            sourceCase,
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
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?, ?,4,'text/plain')",
            TENANT,
            file,
            "evidence-fixture-" + file,
            "a".repeat(64)
        );
        return db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
    }

    EvidenceService.View save(long fileVersion, long version, int order) {
        return evidence.save(
            admin,
            caseId,
            fileVersion,
            version,
            "客户提供",
            OffsetDateTime.parse("2026-09-14T13:20:30+08:00"),
            "合成付款凭证",
            order
        );
    }

    @Test
    void caseDatesPersistAndPatchDistinguishesOmittedFromExplicitNullAndTracksArchive()
        throws Exception {
        login();
        assertEquals(LocalDate.parse("2026-09-15"), cases.get(admin, caseId).filedOn());
        assertEquals("2026-09-15", patchCase(update(0, "ACTIVE"), 200).path("filedOn").asText());
        var changed = update(1, "ACTIVE");
        changed.put("filedOn", "2026-09-16");
        assertEquals("2026-09-16", patchCase(changed, 200).path("filedOn").asText());
        var stale = update(1, "ACTIVE");
        stale.put("filedOn", "2026-09-17");
        patchCase(stale, 409);
        var invalid = update(2, "ACTIVE");
        invalid.put("filedOn", "2026-02-30");
        patchCase(invalid, 400);
        var cleared = update(2, "ACTIVE");
        cleared.put("filedOn", null);
        assertTrue(patchCase(cleared, 200).path("filedOn").isNull());
        var archived = patchCase(update(3, "ARCHIVED"), 200);
        assertFalse(archived.path("archivedAt").isNull());
        patchCase(update(4, "ARCHIVED"), 403);
        assertTrue(patchCase(update(4, "ACTIVE"), 200).path("archivedAt").isNull());
    }

    @Test
    void evidenceHasOriginalVersionUtcTimeStableOrderingAndHttpConflict() throws Exception {
        long first = source(caseId, "first.txt"),
            second = source(caseId, "second.txt");
        login();
        var saved = save(first, 0, 20);
        save(second, 0, 10);
        assertEquals("2026-09-14T05:20:30Z", saved.collectedAt());
        assertEquals(1, saved.version());
        var page = evidence.list(reader, caseId, 1, 20);
        assertFalse(page.canEdit());
        assertEquals(2, page.total());
        assertEquals(Long.toString(second), page.items().getFirst().fileVersionId());
        assertEquals(
            "a".repeat(64),
            db.queryForObject("SELECT sha256 FROM file_version WHERE id=?", String.class, first)
        );
        String endpoint = "/api/v1/cases/" + caseId + "/evidence/" + first;
        String input = json.writeValueAsString(
            Map.of(
                "version",
                1,
                "source",
                "法院调取",
                "collectedAt",
                "2026-09-14T05:20:30Z",
                "description",
                "补充说明",
                "sortOrder",
                1
            )
        );
        mvc.perform(
            put(endpoint)
                .cookie(cookie)
                .header("X-CSRF-TOKEN", csrf)
                .contentType("application/json")
                .content(input)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(
            put(endpoint)
                .cookie(cookie)
                .header("X-CSRF-TOKEN", csrf)
                .contentType("application/json")
                .content(input)
        ).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/cases/" + caseId + "/evidence").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canEdit").value(true))
            .andExpect(jsonPath("$.data.items[0].source").value("法院调取"));
    }

    @Test
    void readerNonmemberAndOtherTenantCannotEditOrGuessOtherCaseVersion() {
        long version = source(caseId, "permission.txt");
        save(version, 0, 0);
        assertThrows(AccessDenied.class, () ->
            evidence.save(reader, caseId, version, 1, "Reader change", null, "", 0)
        );
        assertThrows(AccessDenied.class, () -> evidence.list(outsider, caseId, 1, 20));
        long otherCase = Long.parseLong(
            cases.create(admin, "EVIDENCE-OTHER", "Other", "CIVIL", "").id()
        );
        long otherVersion = source(otherCase, "other.txt");
        assertThrows(AccessDenied.class, () -> save(otherVersion, 0, 0));
        assertNull(evidence.get(reader, caseId, otherVersion));
        db.update(
            "DELETE FROM case_member WHERE tenant_id=? AND case_id=? AND user_id=?",
            TENANT,
            caseId,
            READER
        );
        assertThrows(AccessDenied.class, () -> evidence.get(reader, caseId, version));
    }

    @Test
    void missingAndStaleMetadataCannotOverwriteAndHistoricalCollectionTimeIsSupported() {
        long version = source(caseId, "historical.txt");
        var first = evidence.save(
            admin,
            caseId,
            version,
            0,
            "历史档案",
            OffsetDateTime.parse("1965-03-02T08:30:45+08:00"),
            "",
            0
        );
        assertEquals("1965-03-02T00:30:45Z", first.collectedAt());
        assertEquals(
            409,
            assertThrows(BusinessException.class, () -> save(version, 0, 1)).getCode()
        );
        assertEquals(2, save(version, 1, 1).version());
        assertEquals(
            409,
            assertThrows(BusinessException.class, () -> save(version, 1, 2)).getCode()
        );
        assertEquals(
            2,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='evidence:save'",
                Integer.class,
                TENANT
            )
        );
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                evidence.save(admin, caseId, version, 2, "", null, "", 0)
            ).getCode()
        );
    }

    @Test
    void archivedDeletedUnavailableAndRevokedSourcesCannotBeEditedOrExposed() {
        long version = source(caseId, "deleted.txt");
        save(version, 0, 0);
        cases.update(admin, caseId, 0, "Evidence case", "", "ACTIVE");
        cases.update(admin, caseId, 1, "Evidence case", "", "ARCHIVED");
        assertFalse(evidence.list(admin, caseId, 1, 20).canEdit());
        assertNotNull(evidence.get(reader, caseId, version));
        assertThrows(AccessDenied.class, () -> save(version, 1, 1));
        cases.update(admin, caseId, 2, "Evidence case", "", "ACTIVE");
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND case_id=?",
            TENANT,
            caseId
        );
        assertTrue(evidence.list(admin, caseId, 1, 20).items().isEmpty());
        assertNull(evidence.get(admin, caseId, version));
        assertThrows(AccessDenied.class, () -> save(version, 1, 1));
        db.update(
            "UPDATE file_object SET status='FAILED' WHERE tenant_id=? AND case_id=?",
            TENANT,
            caseId
        );
        assertThrows(AccessDenied.class, () -> save(version, 1, 1));
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='file:read'",
            TENANT,
            TENANT
        );
        assertThrows(AccessDenied.class, () -> evidence.list(admin, caseId, 1, 20));
    }
}
