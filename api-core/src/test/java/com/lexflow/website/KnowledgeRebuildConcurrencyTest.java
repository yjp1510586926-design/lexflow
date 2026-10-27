package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.identity.Identity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Two committed transactions, synchronized after their initial snapshot reads. Fixture is always removed. */
@SpringBootTest(properties = "scheduling.enabled=false")
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class KnowledgeRebuildConcurrencyTest {

    private static final long TENANT = 900000098L;

    @Autowired
    JdbcTemplate db;

    @Autowired
    CaseService cases;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    KnowledgeIndexService indexes;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ObjectMapper json;

    @MockitoSpyBean
    KnowledgeRepository repository;

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry registry) {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        String encoded = Base64.getEncoder().encodeToString(value);
        registry.add("LEXFLOW_AI_MASTER_KEY", () -> encoded);
    }

    @Test
    void concurrentRebuildsAllocateOneJobEvenAfterBothHaveReadOldSnapshots() throws Exception {
        assertEquals(
            0,
            db.queryForObject("SELECT COUNT(*) FROM tenant WHERE id=?", Integer.class, TENANT)
        );
        var executor = Executors.newFixedThreadPool(2);
        var release = new CountDownLatch(1);
        try {
            var actor = new Identity(TENANT, TENANT, 0, "Concurrency fixture");
            long[] source = new TransactionTemplate(transactions).execute(status -> {
                db.update(
                    "INSERT INTO tenant(id,name) VALUES(?,'rebuild-concurrency-only')",
                    TENANT
                );
                // Deliberately unusable credential: this fixture does not log in.
                db.update(
                    "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'rebuild-concurrency-only','unused','Concurrency fixture')",
                    TENANT
                );
                db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", TENANT, TENANT);
                db.update(
                    "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Test admin')",
                    TENANT,
                    TENANT
                );
                db.update(
                    "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
                    TENANT,
                    TENANT,
                    TENANT
                );
                db.update(
                    "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
                    TENANT,
                    TENANT
                );
                long c = Long.parseLong(
                    cases.create(actor, "CONCURRENT", "Concurrent rebuild", "CIVIL", "").id()
                );
                db.update(
                    "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'fixture.txt','AVAILABLE',?)",
                    TENANT,
                    c,
                    TENANT
                );
                long f = db.queryForObject(
                    "SELECT id FROM file_object WHERE tenant_id=?",
                    Long.class,
                    TENANT
                );
                String hash = "f".repeat(64);
                db.update(
                    "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,'concurrency-not-uploaded',?,4,'text/plain')",
                    TENANT,
                    f,
                    hash
                );
                long v = db.queryForObject(
                    "SELECT id FROM file_version WHERE tenant_id=?",
                    Long.class,
                    TENANT
                );
                db.update(
                    "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'concurrency-parse','SUCCEEDED',?)",
                    TENANT,
                    c,
                    v,
                    TENANT
                );
                long t = db.queryForObject(
                    "SELECT id FROM async_task WHERE tenant_id=?",
                    Long.class,
                    TENANT
                );
                db.update(
                    "INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json) VALUES(?,?,?,'text','[{\"kind\":\"PARAGRAPH\",\"index\":1,\"startOffset\":0,\"endOffset\":4}]')",
                    TENANT,
                    t,
                    hash
                );
                long b = Long.parseLong(knowledge.create(actor, c, "Concurrent knowledge").id());
                long d = Long.parseLong(knowledge.prepare(actor, b, t).id());
                configs.save(
                    TENANT,
                    "EMBEDDING",
                    "test",
                    "https://example.test/v1",
                    "embed",
                    "fake-test-key"
                );
                return new long[] { b, d };
            });
            long base = source[0],
                document = source[1];
            long first = Long.parseLong(indexes.request(actor, base, document).taskId());
            var input = indexes.input(first, 0, 1);
            String identity = json.writeValueAsString(
                List.of(input.provider(), input.baseUrl(), input.model())
            );
            var hash = MessageDigest.getInstance("SHA-256").digest(
                identity.getBytes(StandardCharsets.UTF_8)
            );
            String collection = "lexflow_chunks_" + HexFormat.of().formatHex(hash, 0, 8) + "_4_v1";
            assertTrue(indexes.complete(first, 0, 1, true, collection, 4, 1, identity));

            var reached = new CountDownLatch(2);
            doAnswer(call -> {
                Object result = call.callRealMethod();
                reached.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException(
                    "barrier timed out"
                );
                return result;
            })
                .when(repository)
                .base(TENANT, base);
            Callable<Object> submit = () -> {
                try {
                    return indexes.request(actor, base, document);
                } catch (BusinessException conflict) {
                    return conflict;
                }
            };
            Future<Object> a = executor.submit(submit),
                b = executor.submit(submit);
            assertTrue(reached.await(15, TimeUnit.SECONDS));
            release.countDown();
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(
                1,
                results
                    .stream()
                    .filter(KnowledgeIndexService.IndexRequest.class::isInstance)
                    .count()
            );
            assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
            assertEquals(
                2,
                db.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_index_job WHERE tenant_id=?",
                    Integer.class,
                    TENANT
                )
            );
            assertEquals(
                2,
                db.queryForObject(
                    "SELECT last_index_version FROM knowledge_document WHERE id=?",
                    Integer.class,
                    document
                )
            );
            assertEquals(
                1,
                db.queryForObject(
                    "SELECT index_version FROM knowledge_document WHERE id=?",
                    Integer.class,
                    document
                )
            );
            assertEquals(
                2,
                db.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_chunk WHERE tenant_id=?",
                    Integer.class,
                    TENANT
                )
            );
            var won = (KnowledgeIndexService.IndexRequest) results
                .stream()
                .filter(KnowledgeIndexService.IndexRequest.class::isInstance)
                .findFirst()
                .orElseThrow();
            assertEquals(
                Long.valueOf(won.taskId()),
                db.queryForObject(
                    "SELECT pending_index_task_id FROM knowledge_document WHERE id=?",
                    Long.class,
                    document
                )
            );
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
            reset(repository);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                for (String table : List.of(
                    "outbox_event",
                    "knowledge_index_job",
                    "knowledge_chunk",
                    "knowledge_document",
                    "knowledge_base",
                    "task_parse_result",
                    "async_task",
                    "file_version",
                    "file_object",
                    "case_member",
                    "case_info",
                    "audit_log",
                    "ai_configuration",
                    "role_permission",
                    "user_role",
                    "sys_role",
                    "tenant_user"
                ))
                    db.update("DELETE FROM " + table + " WHERE tenant_id=?", TENANT);
                db.update("DELETE FROM sys_user WHERE id=?", TENANT);
                db.update("DELETE FROM tenant WHERE id=?", TENANT);
            });
        }
    }
}
