package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.KnowledgeAccessService;
import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL authorization/publication checks; no external model or object-store calls. */
@SpringBootTest(properties = "scheduling.enabled=false")
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
@Transactional
class TenantKnowledgePublicationTest {

    private static final long TENANT = 910000024L,
        OTHER = 910000025L;

    @Autowired
    JdbcTemplate db;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    KnowledgeAccessService access;

    @Autowired
    KnowledgeIndexService indexes;

    @Autowired
    KnowledgeRepository repository;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    CaseService cases;

    @Autowired
    TaskService tasks;

    @Autowired
    ObjectMapper json;

    private Identity admin, reader, outsider;
    private long base, second;

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry registry) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        registry.add("LEXFLOW_AI_MASTER_KEY", () -> encoded);
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
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'tenant-knowledge-publication-test'),(?,'tenant-knowledge-other-test')",
            TENANT,
            OTHER
        );
        for (long user : List.of(TENANT, TENANT + 100, TENANT + 101, OTHER)) {
            assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, user)
            );
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,'unused','Test member')",
                user,
                "public-kb-test-" + user
            );
            db.update(
                "INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)",
                user == OTHER ? OTHER : TENANT,
                user
            );
        }
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Test admin'),(?,?,'LAWYER','Test reader')",
            TENANT,
            TENANT,
            TENANT + 100,
            TENANT
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?),(?,?,?)",
            TENANT,
            TENANT,
            TENANT,
            TENANT,
            TENANT + 100,
            TENANT + 100
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            TENANT,
            TENANT
        );
        for (String permission : List.of("knowledge:read", "ai:query", "task:read"))
            db.update(
                "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,?)",
                TENANT,
                TENANT + 100,
                permission
            );
        admin = new Identity(TENANT, TENANT, 0, "Admin");
        reader = new Identity(TENANT + 100, TENANT, 0, "Reader");
        outsider = new Identity(OTHER, OTHER, 0, "Other tenant");
        base = Long.parseLong(knowledge.createTenant(admin, "公共资料").id());
        second = Long.parseLong(knowledge.createTenant(admin, "另一个公共库").id());
        configs.save(
            TENANT,
            "EMBEDDING",
            "fixture",
            "https://example.test/v1",
            "embedding",
            "fake-key"
        );
    }

    @Test
    void readerCanReadAndQueryButCannotManageOrReadAnotherTenant() {
        assertEquals("TENANT", knowledge.get(reader, base).scope());
        assertNull(knowledge.get(reader, base).caseId());
        assertFalse((Boolean) knowledge.listTenant(reader).get("canManage"));
        assertEquals(base, access.query(reader, base).id());
        assertThrows(AccessDenied.class, () -> knowledge.createTenant(reader, "Unauthorized"));
        assertThrows(AccessDenied.class, () -> access.manage(reader, base));
        assertThrows(AccessDenied.class, () -> knowledge.get(outsider, base));
        Identity noRole = new Identity(TENANT + 101, TENANT, 0, "No role");
        assertThrows(AccessDenied.class, () -> knowledge.get(noRole, base));
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='ai:query'",
            TENANT,
            TENANT + 100
        );
        assertEquals(base, access.read(reader, base).id());
        assertThrows(AccessDenied.class, () -> access.query(reader, base));
    }

    @Test
    void caseMaterialAndAnotherPublicBaseCannotBePromotedIntoThisBase() {
        long caseId = Long.parseLong(
            cases.create(admin, "KB-PRIVATE", "Private case", "CIVIL", "").id()
        );
        long privateTask = source(null, caseId);
        long otherTask = source(second, null);
        assertThrows(AccessDenied.class, () -> knowledge.prepare(admin, base, privateTask));
        assertThrows(AccessDenied.class, () -> knowledge.prepare(admin, base, otherTask));
        assertTrue(knowledge.documents(reader, base).isEmpty());
        long ownedTask = source(base, null);
        var document = knowledge.prepare(admin, base, ownedTask);
        assertEquals(document.id(), knowledge.prepare(admin, base, ownedTask).id());
        assertEquals(1, knowledge.documents(reader, base).size());
        assertThrows(AccessDenied.class, () -> knowledge.prepare(reader, base, ownedTask));
        assertThrows(AccessDenied.class, () ->
            indexes.request(reader, base, Long.parseLong(document.id()))
        );
    }

    @Test
    void databaseRejectsMissingScopeAndPublicFilePointingToCaseBase() {
        assertThrows(DataAccessException.class, () ->
            db.update(
                "INSERT INTO knowledge_base(tenant_id,name,scope,created_by) VALUES(?,'bad-case','CASE',?)",
                TENANT,
                TENANT
            )
        );
        assertThrows(DataAccessException.class, () ->
            db.update(
                "INSERT INTO file_object(tenant_id,display_name,status,created_by) VALUES(?,'bad.txt','AVAILABLE',?)",
                TENANT,
                TENANT
            )
        );
        long c = Long.parseLong(cases.create(admin, "FK-CASE", "Case base", "CIVIL", "").id());
        long caseBase = Long.parseLong(knowledge.create(admin, c, "Case only").id());
        assertThrows(DataAccessException.class, () ->
            db.update(
                "INSERT INTO file_object(tenant_id,scope,knowledge_base_id,display_name,status,created_by) VALUES(?,'TENANT',?,'bad.txt','AVAILABLE',?)",
                TENANT,
                caseBase,
                TENANT
            )
        );
    }

    @Test
    void cancelledRebuildKeepsPublishedVersionAndLateResultCannotPublish() throws Exception {
        long doc = Long.parseLong(knowledge.prepare(admin, base, source(base, null)).id());
        long first = Long.parseLong(indexes.request(admin, base, doc).taskId());
        var input = indexes.input(first, 0, 1);
        assertNull(input.caseId());
        assertEquals("TENANT", input.scope());
        assertEquals(Long.toString(base), input.knowledgeBaseId());
        String identity = json.writeValueAsString(
            List.of(input.provider(), input.baseUrl(), input.model())
        );
        String collection = collection(identity);
        assertTrue(
            indexes.complete(first, 0, 1, true, collection, 4, input.chunks().size(), identity)
        );
        var old = repository.searchSources(TENANT, base, null).getFirst();
        long chunk = Long.parseLong(input.chunks().getFirst().id());
        assertNotNull(
            repository.citation(TENANT, base, null, doc, chunk, collection, old.indexVersion())
        );
        assertNull(
            repository.citation(OTHER, base, null, doc, chunk, collection, old.indexVersion())
        );
        assertNull(
            repository.citation(TENANT, second, null, doc, chunk, collection, old.indexVersion())
        );
        long rebuild = Long.parseLong(indexes.request(admin, base, doc).taskId());
        assertEquals(old, repository.searchSources(TENANT, base, null).getFirst());
        assertEquals("CANCELLED", tasks.requestCancel(admin, rebuild).status());
        assertFalse(
            indexes.complete(rebuild, 0, 1, true, collection, 4, input.chunks().size(), identity)
        );
        assertEquals(old, repository.searchSources(TENANT, base, null).getFirst());
        assertNotNull(repository.liveCitation(TENANT, base, null, chunk));
    }

    @Test
    void revokedManagerCannotFetchModelCredentialsOrPublishIndexResult() throws Exception {
        long doc = Long.parseLong(knowledge.prepare(admin, base, source(base, null)).id());
        long task = Long.parseLong(indexes.request(admin, base, doc).taskId());
        var input = indexes.input(task, 0, 1);
        String identity = json.writeValueAsString(
            List.of(input.provider(), input.baseUrl(), input.model())
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='knowledge:manage'",
            TENANT,
            TENANT
        );
        assertThrows(AccessDenied.class, () -> indexes.input(task, 0, 1));
        assertTrue(
            indexes.complete(
                task,
                0,
                1,
                true,
                collection(identity),
                4,
                input.chunks().size(),
                identity
            )
        );
        assertEquals(
            "FAILED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, task)
        );
        assertTrue(repository.searchSources(TENANT, base, null).isEmpty());
    }

    @Test
    void deletedSourceCannotRemainVisibleOrPublishDespiteRetainedVectors() throws Exception {
        long doc = Long.parseLong(knowledge.prepare(admin, base, source(base, null)).id());
        long task = Long.parseLong(indexes.request(admin, base, doc).taskId());
        var input = indexes.input(task, 0, 1);
        String identity = json.writeValueAsString(
            List.of(input.provider(), input.baseUrl(), input.model())
        );
        assertTrue(
            indexes.complete(
                task,
                0,
                1,
                true,
                collection(identity),
                4,
                input.chunks().size(),
                identity
            )
        );
        long chunk = Long.parseLong(input.chunks().getFirst().id());
        assertNotNull(repository.liveCitation(TENANT, base, null, chunk));
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND scope='TENANT' AND knowledge_base_id=?",
            TENANT,
            base
        );
        assertTrue(knowledge.documents(reader, base).isEmpty());
        assertTrue(knowledge.chunks(reader, base, doc).isEmpty());
        assertTrue(repository.searchSources(TENANT, base, null).isEmpty());
        assertNull(repository.liveCitation(TENANT, base, null, chunk));
        assertThrows(AccessDenied.class, () -> indexes.request(admin, base, doc));
    }

    private long source(Long baseId, Long caseId) {
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,scope,knowledge_base_id,display_name,status,created_by) VALUES(?,?,?,?,?,'AVAILABLE',?)",
            TENANT,
            caseId,
            baseId == null ? "CASE" : "TENANT",
            baseId,
            "fixture.txt",
            TENANT
        );
        long file = db.queryForObject(
            "SELECT MAX(id) FROM file_object WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        String hash = "a".repeat(64),
            text = "本资料为测试合成内容，合同付款期限为三十日。";
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?, 'text/plain')",
            TENANT,
            file,
            "not-an-object/" + UUID.randomUUID(),
            hash,
            text.getBytes(StandardCharsets.UTF_8).length
        );
        long version = db.queryForObject(
            "SELECT MAX(id) FROM file_version WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,?,'SUCCEEDED',?)",
            TENANT,
            caseId,
            version,
            UUID.randomUUID().toString(),
            TENANT
        );
        long task = db.queryForObject(
            "SELECT MAX(id) FROM async_task WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        db.update(
            "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,?,?)",
            TENANT,
            task,
            hash,
            text,
            "[{\"kind\":\"PARAGRAPH\",\"index\":1,\"startOffset\":0,\"endOffset\":" +
                text.length() +
                "}]"
        );
        return task;
    }

    private static String collection(String identity) throws Exception {
        return (
            "lexflow_chunks_" +
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    identity.getBytes(StandardCharsets.UTF_8)
                ),
                0,
                8
            ) +
            "_4_v1"
        );
    }
}
