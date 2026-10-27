package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lexflow.service.CaseService;
import com.lexflow.service.FileCleanupService;
import com.lexflow.service.FileService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.TaskResultService;
import com.lexflow.service.TaskService;
import com.lexflow.service.TenantKnowledgeFileService;
import com.lexflow.service.TenantKnowledgeUploadMetadataService;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.website.biz.TaskContextBiz;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Dedicated committed fixtures exercise short metadata transactions; only owned rows and objects are removed. */
@SpringBootTest(properties = "scheduling.enabled=false")
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class TenantKnowledgeFileIntegrationTest {

    static final long TENANT = 900000021L,
        OTHER = 900000022L,
        READER = 900000023L;

    @Autowired
    JdbcTemplate db;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    TenantKnowledgeFileService files;

    @Autowired
    FileService caseFiles;

    @Autowired
    TaskService tasks;

    @Autowired
    TaskContextBiz contexts;

    @Autowired
    TaskResultService results;

    @Autowired
    CaseService cases;

    @Autowired
    TenantKnowledgeUploadMetadataService metadata;

    @Autowired
    FileCleanupService cleanup;

    @MockitoSpyBean
    PrivateObjectStorage storage;

    final Identity actor = new Identity(TENANT, TENANT, 0, "Public test admin");
    final Identity reader = new Identity(READER, TENANT, 0, "Public test reader");
    final List<String> objectKeys = new ArrayList<>();
    long base;
    boolean fixtureCreated;
    static final String TEXT = "合成公共知识：验收后十日内付款。\n本材料不含案件个人信息。";

    @BeforeEach
    void fixture() {
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id IN (?,?)",
                Integer.class,
                TENANT,
                OTHER
            ),
            "专用租户存在，禁止覆盖残留数据"
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id IN (?,?)",
                Integer.class,
                TENANT,
                READER
            )
        );
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'public-integration-only'),(?,'other-public-test')",
            TENANT,
            OTHER
        );
        fixtureCreated = true;
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'public-integration-admin','unused-test-hash','Public test admin'),(?,'public-integration-reader','unused-test-hash','Public test reader')",
            TENANT,
            READER
        );
        db.update(
            "INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?),(?,?)",
            TENANT,
            TENANT,
            TENANT,
            READER
        );
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Public test admin'),(?,?,'LAWYER','Public test reader')",
            TENANT,
            TENANT,
            READER,
            TENANT
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?),(?,?,?)",
            TENANT,
            TENANT,
            TENANT,
            TENANT,
            READER,
            READER
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            TENANT,
            TENANT
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'knowledge:read'),(?,?,'task:read'),(?,?,'ai:query')",
            TENANT,
            READER,
            TENANT,
            READER,
            TENANT,
            READER
        );
        base = Long.parseLong(knowledge.createTenant(actor, "公共测试知识库").id());
    }

    @AfterEach
    void cleanOwnedFixture() throws Exception {
        try {
            for (String key : objectKeys) storage.remove(key);
        } finally {
            objectKeys.clear();
            if (fixtureCreated) {
                // The first-use emptiness assertion and creation flag prevent touching earlier data.
                for (String table : List.of(
                    "outbox_event",
                    "knowledge_index_job",
                    "knowledge_chunk",
                    "knowledge_document",
                    "task_parse_result",
                    "async_task",
                    "file_cleanup_job",
                    "file_version",
                    "file_object",
                    "knowledge_base",
                    "case_member",
                    "case_info",
                    "audit_log",
                    "role_permission",
                    "user_role",
                    "sys_role",
                    "tenant_user"
                ))
                    db.update("DELETE FROM " + table + " WHERE tenant_id=?", TENANT);
                db.update("DELETE FROM sys_user WHERE id IN (?,?)", TENANT, READER);
                db.update("DELETE FROM tenant WHERE id IN (?,?)", TENANT, OTHER);
                fixtureCreated = false;
            }
        }
    }

    private TenantKnowledgeFileService.FileView upload() throws Exception {
        var temp = Files.createTempFile("lexflow-public-test-", ".txt");
        try {
            Files.writeString(temp, TEXT, StandardCharsets.UTF_8);
            var view = files.upload(actor, base, temp, "合成法条.txt", "text/plain");
            objectKeys.add(
                db.queryForObject(
                    "SELECT object_key FROM file_version WHERE tenant_id=? AND id=?",
                    String.class,
                    TENANT,
                    Long.parseLong(view.versionId())
                )
            );
            return view;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private TaskService.TaskView parse(TenantKnowledgeFileService.FileView file, String key) {
        return files.parse(
            actor,
            base,
            Long.parseLong(file.id()),
            Long.parseLong(file.versionId()),
            key
        );
    }

    private void succeed(TaskService.TaskView task, TenantKnowledgeFileService.FileView file) {
        results.applyResult(
            Long.parseLong(task.id()),
            0,
            1,
            true,
            null,
            null,
            TEXT,
            file.sha256(),
            List.of(new TaskResultService.SourceSegment("PARAGRAPH", 1, 0, TEXT.length()))
        );
    }

    @Test
    void independentUploadParsePrepareAndReadableByTenantReaderWithoutCasePermissions()
        throws Exception {
        var file = upload();
        long fileId = Long.parseLong(file.id());
        assertNull(file.caseId());
        assertEquals(Long.toString(base), file.knowledgeBaseId());
        assertEquals(
            "TENANT",
            db.queryForObject("SELECT scope FROM file_object WHERE id=?", String.class, fileId)
        );
        assertNull(
            db.queryForObject("SELECT case_id FROM file_object WHERE id=?", Long.class, fileId)
        );
        assertTrue(
            objectKeys.getFirst().startsWith("tenants/" + TENANT + "/knowledge/" + base + "/")
        );
        assertThrows(AccessDenied.class, () -> caseFiles.download(actor, fileId));
        assertThrows(AccessDenied.class, () -> caseFiles.delete(actor, fileId));
        assertArrayEquals(
            TEXT.getBytes(StandardCharsets.UTF_8),
            files.download(reader, base, fileId).bytes()
        );
        assertEquals(1, files.list(reader, base).size());
        var task = parse(file, "public-parse");
        assertNull(task.caseId());
        assertEquals(task.id(), parse(file, "public-parse").id());
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",
                Integer.class,
                TENANT
            )
        );
        var context = contexts.get(Long.parseLong(task.id()));
        assertEquals("TENANT", context.get("scope"));
        assertNull(context.get("case_id"));
        assertEquals(base, ((Number) context.get("knowledge_base_id")).longValue());
        succeed(task, file);
        assertEquals(TEXT, tasks.parsedText(actor, Long.parseLong(task.id())).get("text"));
        var latest = files.list(actor, base).getFirst();
        assertEquals(task.id(), latest.parseTaskId());
        assertEquals("SUCCEEDED", latest.parseStatus());
        var document = knowledge.prepare(actor, base, Long.parseLong(task.id()));
        assertEquals("PREPARED", document.status());
        assertEquals(1, document.chunkCount());
        assertEquals(1, knowledge.documents(reader, base).size());
        assertThrows(AccessDenied.class, () -> tasks.get(reader, Long.parseLong(task.id())));
        assertTrue(tasks.list(reader, 1, 20, null, null).items().isEmpty());
        assertThrows(AccessDenied.class, () ->
            files.parse(reader, base, fileId, Long.parseLong(file.versionId()), "reader-parse")
        );
        assertThrows(AccessDenied.class, () -> files.delete(reader, base, fileId));
        assertThrows(AccessDenied.class, () ->
            files.list(new Identity(TENANT, OTHER, 0, "Other tenant"), base)
        );
    }

    @Test
    void caseSourcesCannotBePublishedIntoPublicLibraryAndPublicCannotEnterCaseLibrary()
        throws Exception {
        var publicFile = upload();
        var publicTask = parse(publicFile, "public-cross-case");
        succeed(publicTask, publicFile);
        long caseId = Long.parseLong(
            cases.create(actor, "PUBLIC-SEPARATION", "Private case", "CIVIL", "").id()
        );
        long caseBase = Long.parseLong(knowledge.create(actor, caseId, "案件私有").id());
        assertThrows(AccessDenied.class, () ->
            knowledge.prepare(actor, caseBase, Long.parseLong(publicTask.id()))
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'private.txt','AVAILABLE',?)",
            TENANT,
            caseId,
            TENANT
        );
        long fileId = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND scope='CASE'",
            Long.class,
            TENANT
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,? ,?,4,'text/plain')",
            TENANT,
            fileId,
            "test-only-not-stored/" + UUID.randomUUID(),
            "a".repeat(64)
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            fileId
        );
        var caseTask = tasks.submitTask(actor, caseId, "DOCUMENT_PARSE", version, "private-parse");
        db.update(
            "UPDATE async_task SET status='SUCCEEDED' WHERE id=?",
            Long.parseLong(caseTask.id())
        );
        db.update(
            "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,'text','[{\"kind\":\"PARAGRAPH\",\"index\":1,\"startOffset\":0,\"endOffset\":4}]')",
            TENANT,
            Long.parseLong(caseTask.id()),
            "a".repeat(64)
        );
        assertThrows(AccessDenied.class, () ->
            knowledge.prepare(actor, base, Long.parseLong(caseTask.id()))
        );
        assertThrows(AccessDenied.class, () ->
            tasks.submitKnowledgeParse(actor, base, version, "forbidden-private-source")
        );
        assertThrows(AccessDenied.class, () ->
            files.parse(actor, base, fileId, version, "forged-file")
        );
        assertThrows(IllegalArgumentException.class, () ->
            tasks.submitTask(actor, null, "DOCUMENT_PARSE", version, "null-case")
        );
        long otherBase = Long.parseLong(knowledge.createTenant(actor, "另一个公共库").id());
        assertThrows(AccessDenied.class, () ->
            files.parse(
                actor,
                otherBase,
                Long.parseLong(publicFile.id()),
                Long.parseLong(publicFile.versionId()),
                "wrong-base"
            )
        );
        assertThrows(AccessDenied.class, () ->
            knowledge.prepare(actor, otherBase, Long.parseLong(publicTask.id()))
        );
        var constraint = assertThrows(org.springframework.dao.DataAccessException.class, () ->
            db.update(
                "INSERT INTO file_object(tenant_id,case_id,knowledge_base_id,scope,display_name,created_by) VALUES(?,?,?,'TENANT','invalid.txt',?)",
                TENANT,
                caseId,
                base,
                TENANT
            )
        );
        var sql = assertInstanceOf(java.sql.SQLException.class, constraint.getMostSpecificCause());
        assertEquals(3819, sql.getErrorCode());
        assertTrue(sql.getMessage().contains("ck_file_scope"));
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_object WHERE tenant_id=? AND display_name='invalid.txt'",
                Integer.class,
                TENANT
            )
        );
    }

    @Test
    void publicParseCancellationIsTerminalAndLateResultCannotPublish() throws Exception {
        var file = upload();
        var task = parse(file, "cancel-public-parse");
        long id = Long.parseLong(task.id());
        assertEquals("CANCELLED", tasks.requestCancel(actor, id).status());
        succeed(task, file);
        assertEquals("CANCELLED", tasks.get(actor, id).status());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM task_parse_result WHERE tenant_id=?",
                Integer.class,
                TENANT
            )
        );
        assertThrows(Exception.class, () -> tasks.parsedText(actor, id));
    }

    @Test
    void removedAdminCannotExecutePublishOrReadPublicTaskAndPermissionRestorationDoesNotForgiveWrongHash()
        throws Exception {
        var file = upload();
        var task = parse(file, "revoked-public-parse");
        long id = Long.parseLong(task.id());
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='knowledge:manage'",
            TENANT,
            TENANT
        );
        assertThrows(AccessDenied.class, () -> contexts.get(id));
        assertThrows(AccessDenied.class, () -> succeed(task, file));
        assertThrows(AccessDenied.class, () -> tasks.get(actor, id));
        assertTrue(tasks.list(actor, 1, 20, null, null).items().isEmpty());
        assertEquals(
            "QUEUED",
            db.queryForObject("SELECT status FROM async_task WHERE id=?", String.class, id)
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'knowledge:manage')",
            TENANT,
            TENANT
        );
        assertThrows(IllegalArgumentException.class, () ->
            results.applyResult(
                id,
                0,
                1,
                true,
                null,
                null,
                TEXT,
                "a".repeat(64),
                List.of(new TaskResultService.SourceSegment("PARAGRAPH", 1, 0, TEXT.length()))
            )
        );
        succeed(task, file);
        assertEquals("SUCCEEDED", tasks.get(actor, id).status());
        db.update(
            "UPDATE tenant_user SET status='DISABLED' WHERE tenant_id=? AND user_id=?",
            TENANT,
            TENANT
        );
        assertThrows(AccessDenied.class, () -> contexts.get(id));
        assertThrows(AccessDenied.class, () -> files.list(actor, base));
    }

    @Test
    void sourceDeletionHidesKnowledgeAndRejectsDownloadContextAndLateParse() throws Exception {
        var file = upload();
        var completed = parse(file, "completed-before-delete");
        succeed(completed, file);
        var document = knowledge.prepare(actor, base, Long.parseLong(completed.id()));
        var pending = parse(file, "pending-before-delete");
        files.delete(actor, base, Long.parseLong(file.id()));
        assertTrue(files.list(reader, base).isEmpty());
        assertTrue(knowledge.documents(reader, base).isEmpty());
        assertTrue(knowledge.chunks(reader, base, Long.parseLong(document.id())).isEmpty());
        assertThrows(AccessDenied.class, () ->
            files.download(reader, base, Long.parseLong(file.id()))
        );
        assertThrows(Exception.class, () -> contexts.get(Long.parseLong(pending.id())));
        assertThrows(IllegalArgumentException.class, () -> succeed(pending, file));
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_cleanup_job WHERE tenant_id=? AND status='PENDING'",
                Integer.class,
                TENANT
            )
        );
    }

    @Test
    void storageSuccessAfterRevocationCannotCompleteUploadAndLeavesCompensation() throws Exception {
        doAnswer(invocation -> {
            objectKeys.add(invocation.getArgument(0));
            invocation.callRealMethod();
            db.update(
                "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='knowledge:manage'",
                TENANT,
                TENANT
            );
            return null;
        })
            .when(storage)
            .put(anyString(), any(Path.class), eq("text/plain"));
        var temp = Files.createTempFile("lexflow-public-denied-upload-", ".txt");
        try {
            Files.writeString(temp, TEXT);
            assertThrows(IllegalStateException.class, () ->
                files.upload(actor, base, temp, "合成.txt", "text/plain")
            );
        } finally {
            Files.deleteIfExists(temp);
        }
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM file_object WHERE tenant_id=?",
                String.class,
                TENANT
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM file_cleanup_job WHERE tenant_id=? AND status='PENDING' AND next_attempt_at<=NOW(6)",
                Integer.class,
                TENANT
            )
        );
        var failed = files.list(actor, base).getFirst();
        // Losing management rights preserves tenant reading rights; an unavailable upload still cannot download or parse.
        assertEquals("FAILED", failed.status());
        assertThrows(AccessDenied.class, () ->
            files.download(actor, base, Long.parseLong(failed.id()))
        );
        assertThrows(AccessDenied.class, () ->
            files.parse(
                actor,
                base,
                Long.parseLong(failed.id()),
                Long.parseLong(failed.versionId()),
                "failed-upload-parse"
            )
        );
    }

    @Test
    void revokeDuringDownloadOrCorruptStorageReturnsNoDocumentBytes() throws Exception {
        var file = upload();
        long id = Long.parseLong(file.id());
        String key = objectKeys.getFirst();
        doReturn(new ByteArrayInputStream("corrupt".getBytes(StandardCharsets.UTF_8)))
            .when(storage)
            .open(key);
        assertThrows(IllegalStateException.class, () -> files.download(reader, base, id));
        doAnswer(invocation -> {
            db.update(
                "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='knowledge:read'",
                TENANT,
                READER
            );
            return new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8));
        })
            .when(storage)
            .open(key);
        assertThrows(AccessDenied.class, () -> files.download(reader, base, id));
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='knowledge:file:download'",
                Integer.class,
                TENANT
            )
        );
    }
}
