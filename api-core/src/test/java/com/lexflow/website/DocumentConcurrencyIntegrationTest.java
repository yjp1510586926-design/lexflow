package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.CaseService;
import com.lexflow.service.DocumentService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL transactions and real session/CSRF; no object uploads, async messages or model calls. */
@SpringBootTest(properties = "scheduling.enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class DocumentConcurrencyIntegrationTest {

    static final long TENANT = 910000245L,
        OTHER = 910000246L,
        EDITOR = 910000345L;
    static final String PASSWORD = "Document-concurrency-test-only!";
    static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    final Identity admin = new Identity(TENANT, TENANT, 0, "Document Test Admin");
    final Identity editor = new Identity(EDITOR, TENANT, 0, "Document Test Editor");
    final Identity outsider = new Identity(OTHER, OTHER, 0, "Other Document Test Admin");

    @Autowired
    JdbcTemplate db;

    @Autowired
    DocumentService documents;

    @Autowired
    CaseService cases;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    org.springframework.session.SessionRepository<?> sessionRepository;

    long caseId;
    boolean fixturesOwned;
    String address;
    final Set<String> ownedSessions = new LinkedHashSet<>(),
        traces = new LinkedHashSet<>();
    final List<Session> sessions = new ArrayList<>();

    record Session(Cookie cookie, String csrf) {}

    record Outcome(boolean success, boolean createsVersion) {}

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
                "SELECT COUNT(*) FROM sys_user WHERE id IN (?,?,?)",
                Integer.class,
                TENANT,
                OTHER,
                EDITOR
            )
        );
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update(
                "INSERT INTO tenant(id,name) VALUES(?,'document-concurrency-only'),(?,'document-concurrency-other')",
                TENANT,
                OTHER
            );
            for (long user : List.of(TENANT, OTHER, EDITOR)) {
                long tenant = user == OTHER ? OTHER : TENANT;
                db.update(
                    "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,?,'Document Tester')",
                    user,
                    "document-only-" + user,
                    PASSWORD_HASH
                );
                db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
                db.update(
                    "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'Document fixture')",
                    user,
                    tenant,
                    user == EDITOR ? "LAWYER" : "TENANT_ADMIN"
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
                        "DOC-" + UUID.randomUUID(),
                        "Synthetic document concurrency case",
                        "CIVIL",
                        ""
                    )
                    .id()
            );
            cases.addMember(admin, caseId, EDITOR, "EDITOR");
        });
        fixturesOwned = true;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        address =
            "fd00::" +
            nonce.substring(0, 4) +
            ":" +
            nonce.substring(4, 8) +
            ":" +
            nonce.substring(8, 12) +
            ":" +
            nonce.substring(12, 16);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            for (var session : sessions)
                perform(
                    post("/api/v1/auth/logout")
                        .cookie(session.cookie())
                        .header("X-CSRF-TOKEN", session.csrf())
                ).andExpect(status().isOk());
        } finally {
            for (String id : ownedSessions) {
                sessionRepository.deleteById(id);
                assertNull(sessionRepository.findById(id));
            }
            if (address != null) redis.delete("lexflow:login:ip:" + address);
            for (String trace : traces)
                db.update(
                    "DELETE FROM audit_log WHERE object_type='http_request' AND trace_id=? AND (tenant_id IS NULL OR tenant_id IN (?,?))",
                    trace,
                    TENANT,
                    OTHER
                );
            if (fixturesOwned) {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    db.update(
                        "UPDATE document SET current_version_id=NULL WHERE tenant_id=?",
                        TENANT
                    );
                    db.update("DELETE FROM document_version WHERE tenant_id=?", TENANT);
                    db.update("DELETE FROM document WHERE tenant_id=?", TENANT);
                    db.update("DELETE FROM case_member WHERE tenant_id=?", TENANT);
                    db.update("DELETE FROM case_info WHERE tenant_id=?", TENANT);
                    db.update("DELETE FROM audit_log WHERE tenant_id IN (?,?)", TENANT, OTHER);
                    db.update(
                        "DELETE FROM role_permission WHERE tenant_id IN (?,?)",
                        TENANT,
                        OTHER
                    );
                    db.update("DELETE FROM user_role WHERE tenant_id IN (?,?)", TENANT, OTHER);
                    db.update("DELETE FROM sys_role WHERE tenant_id IN (?,?)", TENANT, OTHER);
                    db.update("DELETE FROM tenant_user WHERE tenant_id IN (?,?)", TENANT, OTHER);
                    db.update("DELETE FROM sys_user WHERE id IN (?,?,?)", TENANT, OTHER, EDITOR);
                    db.update("DELETE FROM tenant WHERE id IN (?,?)", TENANT, OTHER);
                });
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
                        "SELECT COUNT(*) FROM document WHERE tenant_id=?",
                        Integer.class,
                        TENANT
                    )
                );
                assertEquals(
                    0,
                    db.queryForObject(
                        "SELECT COUNT(*) FROM document_version WHERE tenant_id=?",
                        Integer.class,
                        TENANT
                    )
                );
                assertEquals(
                    0,
                    db.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE tenant_id IN (?,?)",
                        Integer.class,
                        TENANT,
                        OTHER
                    )
                );
            }
        }
    }

    DocumentService.DocumentView create() {
        return documents.create(admin, caseId, "Synthetic editable document", "初始正文");
    }

    long id(DocumentService.DocumentView doc) {
        return Long.parseLong(doc.id());
    }

    long current(DocumentService.DocumentView doc) {
        return Long.parseLong(doc.currentVersionId());
    }

    DocumentService.DocumentView live(DocumentService.DocumentView doc) {
        return documents
            .list(admin, caseId)
            .stream()
            .filter(d -> d.id().equals(doc.id()))
            .findFirst()
            .orElseThrow();
    }

    int versions(DocumentService.DocumentView doc) {
        return db.queryForObject(
            "SELECT COUNT(*) FROM document_version WHERE tenant_id=? AND document_id=?",
            Integer.class,
            TENANT,
            id(doc)
        );
    }

    void conflict(Runnable action) {
        assertEquals(409, assertThrows(BusinessException.class, action::run).getCode());
    }

    @Test
    void staleEditorCannotAppendRestoreOrPublishAndNeitherTokenAloneIsEnough() {
        var baseline = create();
        var saved = documents.addVersion(
            editor,
            caseId,
            id(baseline),
            "另一位编辑的新正文",
            baseline.version(),
            current(baseline)
        );
        var live = live(baseline);
        assertEquals(2, versions(baseline));
        assertEquals(baseline.version() + 1, live.version());
        conflict(() ->
            documents.addVersion(
                admin,
                caseId,
                id(baseline),
                "过期的未保存正文",
                baseline.version(),
                current(baseline)
            )
        );
        conflict(() ->
            documents.addVersion(
                admin,
                caseId,
                id(baseline),
                "过期正文",
                live.version(),
                current(baseline)
            )
        );
        conflict(() ->
            documents.addVersion(
                admin,
                caseId,
                id(baseline),
                "过期正文",
                baseline.version(),
                current(live)
            )
        );
        conflict(() ->
            documents.restoreVersion(
                admin,
                caseId,
                id(baseline),
                current(baseline),
                baseline.version(),
                current(baseline)
            )
        );
        conflict(() ->
            documents.publish(admin, caseId, id(baseline), baseline.version(), current(baseline))
        );
        assertEquals(live, live(baseline));
        assertEquals(saved.id(), live.currentVersionId());
        assertEquals(2, versions(baseline));
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='document:version:add' AND object_id=?",
                Integer.class,
                TENANT,
                baseline.id()
            )
        );
    }

    @Test
    void publishingChangesVersionEvenWithTheSamePointerAndFreshSaveAndRestoreStayImmutable() {
        var baseline = create();
        var published = documents.publish(
            editor,
            caseId,
            id(baseline),
            baseline.version(),
            current(baseline)
        );
        assertEquals("PUBLISHED", published.status());
        assertEquals(current(baseline), current(published));
        assertEquals(baseline.version() + 1, published.version());
        assertEquals(1, versions(baseline));
        conflict(() ->
            documents.addVersion(
                admin,
                caseId,
                id(baseline),
                "旧草稿",
                baseline.version(),
                current(baseline)
            )
        );
        conflict(() ->
            documents.restoreVersion(
                admin,
                caseId,
                id(baseline),
                current(baseline),
                baseline.version(),
                current(baseline)
            )
        );
        conflict(() ->
            documents.publish(admin, caseId, id(baseline), baseline.version(), current(baseline))
        );
        documents.addVersion(
            admin,
            caseId,
            id(baseline),
            "新版正文",
            published.version(),
            current(published)
        );
        var edited = live(baseline);
        assertEquals("DRAFT", edited.status());
        var restored = documents.restoreVersion(
            admin,
            caseId,
            id(baseline),
            current(baseline),
            edited.version(),
            current(edited)
        );
        assertEquals(3, restored.versionNo());
        assertEquals("初始正文", restored.contentText());
        assertEquals(3, versions(baseline));
        assertEquals(4, live(baseline).version());
        assertEquals(
            "初始正文",
            db.queryForObject(
                "SELECT content_text FROM document_version WHERE tenant_id=? AND id=?",
                String.class,
                TENANT,
                current(baseline)
            )
        );
    }

    @Test
    void concurrentEditorsAndMixedMutationsCommitExactlyOneIntent() throws Exception {
        for (String pair : List.of("SAVE_SAVE", "SAVE_RESTORE", "SAVE_PUBLISH")) {
            var baseline = create();
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(2);
            try {
                Callable<Outcome> save = () ->
                    mutate(
                        () ->
                            documents.addVersion(
                                admin,
                                caseId,
                                id(baseline),
                                "并发人工正文",
                                baseline.version(),
                                current(baseline)
                            ),
                        true,
                        ready,
                        start
                    );
                Callable<Outcome> other = () ->
                    mutate(
                        () -> {
                            if (pair.equals("SAVE_PUBLISH")) documents.publish(
                                editor,
                                caseId,
                                id(baseline),
                                baseline.version(),
                                current(baseline)
                            );
                            else if (pair.equals("SAVE_RESTORE")) documents.restoreVersion(
                                editor,
                                caseId,
                                id(baseline),
                                current(baseline),
                                baseline.version(),
                                current(baseline)
                            );
                            else documents.addVersion(
                                editor,
                                caseId,
                                id(baseline),
                                "另一并发正文",
                                baseline.version(),
                                current(baseline)
                            );
                        },
                        !pair.equals("SAVE_PUBLISH"),
                        ready,
                        start
                    );
                var first = pool.submit(save);
                var second = pool.submit(other);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                var a = first.get(15, TimeUnit.SECONDS);
                var b = second.get(15, TimeUnit.SECONDS);
                assertNotEquals(a.success(), b.success(), pair);
                var winner = a.success() ? a : b;
                assertEquals(1 + (winner.createsVersion() ? 1 : 0), versions(baseline), pair);
                assertEquals(baseline.version() + 1, live(baseline).version(), pair);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
            }
        }
    }

    Outcome mutate(
        Runnable action,
        boolean createsVersion,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            action.run();
            return new Outcome(true, createsVersion);
        } catch (BusinessException failure) {
            assertEquals(409, failure.getCode());
            return new Outcome(false, false);
        }
    }

    @Test
    void unreviewedAiDraftsRemainUnpublishableAndApprovalInvalidatesOldEditors() {
        var baseline = create();
        db.update(
            "INSERT INTO document_version(tenant_id,document_id,version_no,content_text,source,review_status,created_by) VALUES(?,?,2,'合成待审 AI 草稿','AI_DRAFT','PENDING_REVIEW',?)",
            TENANT,
            id(baseline),
            TENANT
        );
        long ai = db.queryForObject(
            "SELECT id FROM document_version WHERE tenant_id=? AND document_id=? AND version_no=2",
            Long.class,
            TENANT,
            id(baseline)
        );
        conflict(() ->
            documents.restoreVersion(
                admin,
                caseId,
                id(baseline),
                ai,
                baseline.version(),
                current(baseline)
            )
        );
        assertEquals(2, versions(baseline));
        assertEquals(current(baseline), current(live(baseline)));
        // Simulate a legacy current pointer to an unreviewed draft and exercise existing publish/export guards.
        db.update(
            "UPDATE document SET current_version_id=? WHERE tenant_id=? AND id=?",
            ai,
            TENANT,
            id(baseline)
        );
        var pending = live(baseline);
        conflict(() ->
            documents.publish(admin, caseId, id(pending), pending.version(), current(pending))
        );
        conflict(() -> documents.getExportData(admin, caseId, id(pending), ai));
        documents.reviewVersion(editor, caseId, id(baseline), ai, "APPROVED");
        var reviewed = live(baseline);
        assertEquals(pending.version() + 1, reviewed.version());
        conflict(() ->
            documents.publish(admin, caseId, id(pending), pending.version(), current(pending))
        );
        documents.publish(admin, caseId, id(reviewed), reviewed.version(), current(reviewed));
        assertEquals(
            "合成待审 AI 草稿",
            documents.getExportData(admin, caseId, id(reviewed), ai).content()
        );
        assertEquals(2, versions(baseline));
    }

    @Test
    void versionAllocationIncludesAnAiDraftCommittedAfterTheEditorsReadSnapshot() throws Exception {
        var baseline = create();
        var snapshotRead = new CountDownLatch(1);
        var draftSaved = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var save = pool.submit(() ->
                new TransactionTemplate(transactions).execute(status -> {
                    // Establish an older MySQL REPEATABLE READ view before an independent draft arrives.
                    assertEquals(1, versions(baseline));
                    snapshotRead.countDown();
                    try {
                        assertTrue(draftSaved.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return documents.addVersion(
                        editor,
                        caseId,
                        id(baseline),
                        "合并后的人工正文",
                        baseline.version(),
                        current(baseline)
                    );
                })
            );
            assertTrue(snapshotRead.await(5, TimeUnit.SECONDS));
            var draft = documents.createAiDraftVersion(
                admin,
                caseId,
                id(baseline),
                "独立待审 AI 草稿",
                910000445L
            );
            assertEquals(2, draft.versionNo());
            assertEquals(baseline, live(baseline));
            draftSaved.countDown();
            var saved = save.get(15, TimeUnit.SECONDS);
            assertEquals(3, saved.versionNo());
            assertEquals(3, versions(baseline));
            assertEquals(
                "PENDING_REVIEW",
                documents
                    .listVersions(admin, caseId, id(baseline))
                    .stream()
                    .filter(v -> v.id().equals(draft.id()))
                    .findFirst()
                    .orElseThrow()
                    .reviewStatus()
            );
        } finally {
            draftSaved.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void independentAiDraftUsesAUniqueNumberWithoutReplacingAManualEditCommittedAfterItsSnapshot()
        throws Exception {
        var baseline = create();
        var snapshotRead = new CountDownLatch(1);
        var manualSaved = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var draft = pool.submit(() ->
                new TransactionTemplate(transactions).execute(status -> {
                    assertEquals(1, versions(baseline));
                    snapshotRead.countDown();
                    try {
                        assertTrue(manualSaved.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return documents.createAiDraftVersion(
                        editor,
                        caseId,
                        id(baseline),
                        "基于旧正文独立生成的待审草稿",
                        910000446L
                    );
                })
            );
            assertTrue(snapshotRead.await(5, TimeUnit.SECONDS));
            var manual = documents.addVersion(
                admin,
                caseId,
                id(baseline),
                "AI 生成期间人工保存的新正文",
                baseline.version(),
                current(baseline)
            );
            var savedDocument = live(baseline);
            manualSaved.countDown();
            var result = draft.get(15, TimeUnit.SECONDS);
            assertEquals(3, result.versionNo());
            assertEquals("PENDING_REVIEW", result.reviewStatus());
            assertEquals(savedDocument, live(baseline));
            assertEquals(manual.id(), live(baseline).currentVersionId());
            assertEquals(3, versions(baseline));
            assertEquals(
                result,
                documents.createAiDraftVersion(
                    editor,
                    caseId,
                    id(baseline),
                    result.contentText(),
                    910000446L
                )
            );
            conflict(() ->
                documents.createAiDraftVersion(
                    editor,
                    caseId,
                    id(baseline),
                    "相同任务的不同正文",
                    910000446L
                )
            );
            assertEquals(3, versions(baseline));
            assertEquals(savedDocument, live(baseline));
        } finally {
            manualSaved.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void authorizationCannotBeBypassedWithKnownCurrentVersionTokens() {
        var baseline = create();
        assertThrows(AccessDenied.class, () ->
            documents.addVersion(
                outsider,
                caseId,
                id(baseline),
                "跨租户",
                baseline.version(),
                current(baseline)
            )
        );
        assertThrows(AccessDenied.class, () ->
            documents.restoreVersion(
                outsider,
                caseId,
                id(baseline),
                current(baseline),
                baseline.version(),
                current(baseline)
            )
        );
        assertThrows(AccessDenied.class, () ->
            documents.publish(outsider, caseId, id(baseline), baseline.version(), current(baseline))
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='document:write'",
            TENANT,
            EDITOR
        );
        assertThrows(AccessDenied.class, () ->
            documents.addVersion(
                editor,
                caseId,
                id(baseline),
                "撤权",
                baseline.version(),
                current(baseline)
            )
        );
        assertEquals(baseline, live(baseline));
        assertEquals(1, versions(baseline));
    }

    @Test
    void documentAndVersionHttpTimestampsIdentifyTheSameStoredUtcInstants() throws Exception {
        var document = create();
        var version = documents.listVersions(admin, caseId, id(document)).getFirst();
        assertTrue(document.createdAt().endsWith("Z"));
        assertTrue(version.createdAt().endsWith("Z"));
        assertEquals(
            db.queryForObject(
                "SELECT CAST(UNIX_TIMESTAMP(created_at)*1000000 AS SIGNED) FROM document WHERE tenant_id=? AND id=?",
                Long.class,
                TENANT,
                id(document)
            ),
            epochMicros(document.createdAt())
        );
        assertEquals(
            db.queryForObject(
                "SELECT CAST(UNIX_TIMESTAMP(created_at)*1000000 AS SIGNED) FROM document_version WHERE tenant_id=? AND id=?",
                Long.class,
                TENANT,
                current(document)
            ),
            epochMicros(version.createdAt())
        );
        var session = login();
        String path = "/api/v1/cases/" + caseId + "/documents";
        var listed = perform(get(path).cookie(session.cookie()))
            .andExpect(status().isOk())
            .andReturn();
        var history = perform(
            get(path + "/" + document.id() + "/versions").cookie(session.cookie())
        )
            .andExpect(status().isOk())
            .andReturn();
        assertEquals(
            document.createdAt(),
            json
                .readTree(listed.getResponse().getContentAsString())
                .at("/data/0/createdAt")
                .asText()
        );
        assertEquals(
            version.createdAt(),
            json
                .readTree(history.getResponse().getContentAsString())
                .at("/data/0/createdAt")
                .asText()
        );
    }

    static long epochMicros(String value) {
        var instant = Instant.parse(value);
        return instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1_000;
    }

    @Test
    void realHttpRequiresBothExpectedTokensAndStaleRequestsCannotMutate() throws Exception {
        var baseline = create();
        var session = login();
        String path = "/api/v1/cases/" + caseId + "/documents/" + baseline.id();
        for (String route : List.of(
            "/versions",
            "/publish",
            "/restore/" + baseline.currentVersionId()
        )) {
            for (Map<String, Object> body : List.of(
                Map.<String, Object>of("content", "缺少令牌"),
                Map.<String, Object>of(
                    "content",
                    "少一个令牌",
                    "expectedVersion",
                    baseline.version()
                ),
                Map.<String, Object>of(
                    "content",
                    "少一个令牌",
                    "expectedCurrentVersionId",
                    baseline.currentVersionId()
                )
            )) {
                perform(
                    post(path + route)
                        .cookie(session.cookie())
                        .header("X-CSRF-TOKEN", session.csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(body))
                ).andExpect(status().isBadRequest());
            }
        }
        assertEquals(1, versions(baseline));
        assertEquals(baseline, live(baseline));
        var body = new LinkedHashMap<String, Object>();
        body.put("content", "HTTP 新正文");
        body.put("expectedVersion", baseline.version());
        body.put("expectedCurrentVersionId", baseline.currentVersionId());
        perform(
            post(path + "/versions")
                .cookie(session.cookie())
                .contentType("application/json")
                .content(json.writeValueAsString(body))
        ).andExpect(status().isForbidden());
        perform(
            post(path + "/versions")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body))
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.versionNo").value(2));
        var saved = live(baseline);
        for (String route : List.of(
            "/versions",
            "/publish",
            "/restore/" + baseline.currentVersionId()
        )) {
            perform(
                post(path + route)
                    .cookie(session.cookie())
                    .header("X-CSRF-TOKEN", session.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body))
            ).andExpect(status().isConflict());
        }
        assertEquals(2, versions(baseline));
        assertEquals(saved, live(baseline));
        body.put("expectedVersion", saved.version());
        body.put("expectedCurrentVersionId", saved.currentVersionId());
        perform(
            post(path + "/publish")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", session.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body))
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(saved.version() + 1))
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andDo(result -> {
            String trace = result.getResponse().getHeader("X-Trace-Id");
            if (trace != null) traces.add(trace);
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
                    ownedSessions.add(id);
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

    Session login() throws Exception {
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
                            "document-only-" + TENANT,
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
        var session = csrf(MockCookie.parse(result.getResponse().getHeader("Set-Cookie")));
        sessions.add(session);
        return session;
    }
}
