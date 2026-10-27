package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.KnowledgeVectorArtifactRepository;
import com.lexflow.dao.KnowledgeVectorArtifactRepository.Artifact;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.KnowledgeIndexService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.KnowledgeVectorArtifactService;
import com.lexflow.service.TaskService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.core.storage.KnowledgeVectorRetirementClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual MySQL transactions + private Node HTTP + real Milvus; all vectors and schemas are synthetic. */
@SpringBootTest(
    properties = { "scheduling.enabled=false", "lexflow.audit.offline-buffer.enabled=false" }
)
@EnabledIfEnvironmentVariable(named = "LEXFLOW_VECTOR_RETIREMENT_INTEGRATION", matches = "true")
class KnowledgeVectorRetirementIntegrationTest {

    static final long TENANT = 190001;
    static final String MODEL = "vector-retirement-" + UUID.randomUUID();
    static final String SECRET = UUID.randomUUID().toString();

    @Autowired
    JdbcTemplate db;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    KnowledgeIndexService indexes;

    @Autowired
    KnowledgeVectorArtifactService retirement;

    @Autowired
    KnowledgeVectorArtifactRepository locations;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    TaskService tasks;

    @Autowired
    ObjectMapper json;

    Identity actor = new Identity(TENANT, TENANT, 0, "Synthetic vector owner");

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        String url = System.getenv("LEXFLOW_VECTOR_DATABASE_URL");
        if (
            url == null ||
            !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/lexflow_vector_[a-f0-9]{32}\\?.*")
        ) throw new IllegalStateException("Vector integration requires its owned fresh schema");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.datasource.username", () ->
            System.getenv("LEXFLOW_VECTOR_DATABASE_USER")
        );
        registry.add("spring.flyway.user", () -> System.getenv("LEXFLOW_VECTOR_DATABASE_USER"));
        registry.add("spring.datasource.password", () ->
            System.getenv("LEXFLOW_VECTOR_DATABASE_PASSWORD")
        );
        registry.add("spring.flyway.password", () ->
            System.getenv("LEXFLOW_VECTOR_DATABASE_PASSWORD")
        );
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        registry.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(key));
        registry.add(
            "spring.session.redis.namespace",
            () -> "lexflow:vector-retirement:" + UUID.randomUUID()
        );
    }

    @Test
    void realRetirementPreservesPublishedScopesAndRepeatedlyDeletesLateWrites() throws Exception {
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class));
        db.update("INSERT INTO tenant(id,name) VALUES(?,'isolated vector retirement')", TENANT);
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'vector-only','unused','Synthetic')",
            TENANT
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", TENANT, TENANT);
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Synthetic')",
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
        long base = Long.parseLong(knowledge.createTenant(actor, "Synthetic vector base").id());
        configs.save(
            TENANT,
            "EMBEDDING",
            "fixture",
            "https://localhost/vector-only",
            MODEL,
            "never-used-key"
        );
        long document = source(base);
        long first = Long.parseLong(indexes.request(actor, base, document).taskId());
        var input = indexes.input(first, 0, 1);
        String identity = json.writeValueAsString(
            List.of(input.provider(), input.baseUrl(), input.model())
        );
        String collection = KnowledgeVectorArtifactService.collectionName(identity, 4);
        List<Map<String, Object>> guards = new ArrayList<>();
        guards.add(row("800001", input, 1, "tenantId", "190002"));
        guards.add(row("800002", input, 1, "knowledgeBaseId", "800002"));
        guards.add(row("800003", input, 1, "documentId", "800003"));
        guards.add(row("800004", input, 1, "fileVersionId", "800004"));
        var caseGuard = row("800005", input, 1, "caseId", "800005");
        caseGuard.put("scope", "CASE");
        guards.add(caseGuard);
        try (var node = new VectorNode(json)) {
            var client = new KnowledgeVectorRetirementClient(
                json,
                "http://127.0.0.1:" + node.port,
                SECRET
            );
            try {
                indexes.registerVectors(first, 0, 1, collection, 4, identity);
                indexes.registerVectors(first, 0, 1, collection, 4, identity);
                long firstLocation = location(first);
                var seed = new ArrayList<>(guards);
                seed.add(row(input.chunks().getFirst().id(), input, 1, null, null));
                node.seed(identity, seed);
                assertNull(
                    retirement.reserve(firstLocation),
                    "pending authorized vectors stay available"
                );
                assertTrue(
                    indexes.complete(
                        first,
                        0,
                        1,
                        true,
                        collection,
                        4,
                        input.chunks().size(),
                        identity
                    )
                );
                // Exercise the exact migration backfill on a populated legacy publication.
                db.update("DELETE FROM knowledge_vector_artifact WHERE id=?", firstLocation);
                Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
                if (project.getFileName().toString().equals("api-core")) project =
                    project.getParent();
                String migration = java.nio.file.Files.readString(
                    project.resolve(
                        "service/src/main/resources/db/migration/V26__knowledge_vector_retirement.sql"
                    )
                );
                db.execute(
                    migration.substring(migration.indexOf("INSERT INTO knowledge_vector_artifact"))
                );
                firstLocation = location(first);
                due(firstLocation);
                assertNull(retirement.reserve(firstLocation), "published version is never retired");
                assertEquals(
                    1,
                    db.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_vector_artifact WHERE task_id=?",
                        Integer.class,
                        first
                    )
                );

                long rebuild = Long.parseLong(indexes.request(actor, base, document).taskId());
                var rebuilt = indexes.input(rebuild, 0, 1);
                indexes.registerVectors(rebuild, 0, 1, collection, 4, identity);
                long abandoned = location(rebuild);
                String abandonedChunk = rebuilt.chunks().getFirst().id();
                node.seed(
                    identity,
                    List.of(row(abandonedChunk, rebuilt, rebuilt.indexVersion(), null, null))
                );
                assertEquals("CANCELLED", tasks.requestCancel(actor, rebuild).status());
                assertThrows(RuntimeException.class, () ->
                    indexes.registerVectors(rebuild, 0, 1, collection, 4, identity)
                );
                Artifact lease = retirement.reserve(abandoned);
                assertNotNull(lease);
                assertNull(retirement.reserve(abandoned), "only one lease owns deletion");
                assertFalse(
                    new KnowledgeVectorRetirementClient(
                        json,
                        "http://127.0.0.1:" + node.port,
                        "wrong-secret"
                    ).delete(lease)
                );
                assertTrue(node.ids(collection).contains(abandonedChunk));
                assertTrue(client.delete(lease));
                retirement.finish(lease, true);
                assertTrue(locations.find(abandoned).retired());
                assertFalse(node.ids(collection).contains(abandonedChunk));
                assertTrue(node.ids(collection).contains(input.chunks().getFirst().id()));
                for (var g : guards)
                    assertTrue(
                        node.ids(collection).contains(g.get("chunkId")),
                        "different exact scope survives"
                    );
                // Deliberately simulate a worker which passed authorization just before cancellation.
                node.seed(
                    identity,
                    List.of(row(abandonedChunk, rebuilt, rebuilt.indexVersion(), null, null))
                );
                due(abandoned);
                Artifact sweep = retirement.reserve(abandoned);
                assertNotNull(sweep);
                assertTrue(client.delete(sweep));
                retirement.finish(sweep, true);
                assertFalse(node.ids(collection).contains(abandonedChunk));
                assertEquals(
                    1,
                    db.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_vector_artifact WHERE id=? AND retired_at IS NOT NULL",
                        Integer.class,
                        abandoned
                    )
                );

                long replacement = Long.parseLong(indexes.request(actor, base, document).taskId());
                var replaced = indexes.input(replacement, 0, 1);
                String replacementChunk = replaced.chunks().getFirst().id();
                indexes.registerVectors(replacement, 0, 1, collection, 4, identity);
                node.seed(
                    identity,
                    List.of(row(replacementChunk, replaced, replaced.indexVersion(), null, null))
                );
                assertTrue(
                    indexes.complete(
                        replacement,
                        0,
                        1,
                        true,
                        collection,
                        4,
                        replaced.chunks().size(),
                        identity
                    )
                );
                long replacementLocation = location(replacement);
                due(replacementLocation);
                assertNull(retirement.reserve(replacementLocation));
                due(firstLocation);
                Artifact old = retirement.reserve(firstLocation);
                assertNotNull(old);
                assertTrue(client.delete(old));
                retirement.finish(old, true);
                assertFalse(node.ids(collection).contains(input.chunks().getFirst().id()));
                assertTrue(node.ids(collection).contains(replacementChunk));

                db.update(
                    "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND knowledge_base_id=?",
                    TENANT,
                    base
                );
                due(replacementLocation);
                Artifact deleted = retirement.reserve(replacementLocation);
                assertNotNull(deleted);
                assertTrue(client.delete(deleted));
                retirement.finish(deleted, true);
                assertFalse(node.ids(collection).contains(replacementChunk));
                for (var g : guards) assertTrue(node.ids(collection).contains(g.get("chunkId")));
                assertTrue(
                    db.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='knowledge:vector:cleanup' AND result='SUCCESS'",
                        Integer.class,
                        TENANT
                    ) >= 4
                );

                // A failed task's partial vectors are retired; recoverable WAITING_HUMAN is retained first.
                long secondDocument = source(base);
                long failed = Long.parseLong(indexes.request(actor, base, secondDocument).taskId());
                var failureInput = indexes.input(failed, 0, 1);
                String failureChunk = failureInput.chunks().getFirst().id();
                indexes.registerVectors(failed, 0, 1, collection, 4, identity);
                long failedLocation = location(failed);
                node.seed(identity, List.of(row(failureChunk, failureInput, 1, null, null)));
                db.update("UPDATE async_task SET status='WAITING_HUMAN' WHERE id=?", failed);
                due(failedLocation);
                assertNull(retirement.reserve(failedLocation));
                db.update("UPDATE async_task SET status='QUEUED' WHERE id=?", failed);
                assertTrue(indexes.complete(failed, 0, 1, false, null, null, null, null));
                due(failedLocation);
                // An audit write failure must roll back retirement before any physical deletion.
                db.execute("RENAME TABLE audit_log TO audit_log_vector_fault");
                try {
                    assertThrows(RuntimeException.class, () -> retirement.reserve(failedLocation));
                } finally {
                    db.execute("RENAME TABLE audit_log_vector_fault TO audit_log");
                }
                assertFalse(locations.find(failedLocation).retired());
                assertTrue(node.ids(collection).contains(failureChunk));
                Artifact failure = retirement.reserve(failedLocation);
                assertNotNull(failure);
                retirement.finish(failure, false);
                assertTrue(
                    db.queryForObject(
                        "SELECT cleanup_attempt FROM knowledge_vector_artifact WHERE id=?",
                        Integer.class,
                        failedLocation
                    ) > 0
                );
                assertEquals(
                    "FAILED",
                    db.queryForObject(
                        "SELECT result FROM audit_log WHERE tenant_id=? AND action='knowledge:vector:cleanup' ORDER BY id DESC LIMIT 1",
                        String.class,
                        TENANT
                    )
                );
                due(failedLocation);
                Artifact retry = retirement.reserve(failedLocation);
                assertNotEquals(failure.leaseToken(), retry.leaseToken());
                retirement.finish(failure, true);
                assertEquals(
                    retry.leaseToken(),
                    locations.find(failedLocation).leaseToken(),
                    "stale completion cannot clear new lease"
                );
                assertTrue(client.delete(retry));
                retirement.finish(retry, true);
                assertFalse(node.ids(collection).contains(failureChunk));
                assertFalse(
                    client.delete(
                        new Artifact(
                            retry.id(),
                            retry.tenantId(),
                            retry.baseId(),
                            retry.caseId(),
                            retry.scope(),
                            retry.documentId(),
                            retry.fileVersionId(),
                            retry.indexVersion(),
                            retry.taskId(),
                            "wrong-collection",
                            retry.dimension(),
                            retry.modelIdentity(),
                            true,
                            retry.leaseToken(),
                            0
                        )
                    )
                );
            } finally {
                assertTrue(
                    node
                        .command(Map.of("action", "drop", "collectionName", collection))
                        .path("absent")
                        .booleanValue()
                );
            }
        }
    }

    long location(long task) {
        return db.queryForObject(
            "SELECT id FROM knowledge_vector_artifact WHERE task_id=?",
            Long.class,
            task
        );
    }

    void due(long id) {
        db.update(
            "UPDATE knowledge_vector_artifact SET next_check_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
            id
        );
    }

    long source(long base) {
        db.update(
            "INSERT INTO file_object(tenant_id,scope,knowledge_base_id,display_name,status,created_by) VALUES(?,'TENANT',?,'synthetic.txt','AVAILABLE',?)",
            TENANT,
            base,
            TENANT
        );
        long file = db.queryForObject(
            "SELECT MAX(id) FROM file_object WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        String hash = "a".repeat(64),
            text = "此内容仅用于无模型调用的物理向量回收验收。";
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,?,'text/plain')",
            TENANT,
            file,
            "vector-only/" + UUID.randomUUID(),
            hash,
            text.getBytes(StandardCharsets.UTF_8).length
        );
        long version = db.queryForObject(
            "SELECT MAX(id) FROM file_version WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        db.update(
            "INSERT INTO async_task(tenant_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,'DOCUMENT_PARSE',?,?,'SUCCEEDED',?)",
            TENANT,
            version,
            UUID.randomUUID().toString(),
            TENANT
        );
        long parse = db.queryForObject(
            "SELECT MAX(id) FROM async_task WHERE tenant_id=?",
            Long.class,
            TENANT
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
        return Long.parseLong(knowledge.prepare(actor, base, parse).id());
    }

    Map<String, Object> row(
        String id,
        KnowledgeIndexService.IndexInput input,
        int index,
        String key,
        Object value
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("chunkId", id);
        result.put("tenantId", input.tenantId());
        result.put("scope", input.scope());
        result.put("caseId", input.caseId());
        result.put("knowledgeBaseId", input.knowledgeBaseId());
        result.put("documentId", input.documentId());
        result.put("fileVersionId", input.fileVersionId());
        result.put("indexVersion", index);
        result.put("embedding", List.of(1, 0, 0, 0));
        if (key != null) result.put(key, value);
        return result;
    }

    static class VectorNode implements AutoCloseable {

        final Process process;
        final BufferedReader output;
        final BufferedWriter input;
        final ObjectMapper json;
        final int port;

        VectorNode(ObjectMapper json) throws Exception {
            this.json = json;
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            if (root.getFileName().toString().equals("api-core")) root = root.getParent();
            var builder = new ProcessBuilder(
                "node",
                root.resolve("ai-service/test/support/vector-retirement-server.cjs").toString()
            );
            builder.environment().put("INTERNAL_SECRET", SECRET);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );
            input = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
            );
            port = receive().path("port").intValue();
            assertTrue(port > 0);
        }

        com.fasterxml.jackson.databind.JsonNode receive() throws Exception {
            String line;
            while ((line = output.readLine()) != null)
                if (line.startsWith("LEXFLOW_VECTOR ")) {
                    var result = json.readTree(line.substring(15));
                    assertFalse(result.path("failed").booleanValue(), result.toString());
                    return result;
                }
            throw new IOException("vector harness terminated");
        }

        com.fasterxml.jackson.databind.JsonNode command(Map<String, ?> command) throws Exception {
            input.write(json.writeValueAsString(command));
            input.newLine();
            input.flush();
            return receive();
        }

        void seed(String model, List<Map<String, Object>> rows) throws Exception {
            assertTrue(
                command(Map.of("action", "seed", "modelIdentity", model, "records", rows))
                    .path("seeded")
                    .booleanValue()
            );
        }

        List<String> ids(String collection) throws Exception {
            var result = new ArrayList<String>();
            command(Map.of("action", "query", "collectionName", collection))
                .path("ids")
                .forEach(v -> result.add(v.textValue()));
            return result;
        }

        public void close() throws Exception {
            input.close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            assertEquals(0, process.exitValue());
        }
    }
}
