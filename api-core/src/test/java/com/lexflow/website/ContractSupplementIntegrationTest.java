package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.ContractReviewService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.TaskService;
import com.lexflow.service.file.PrivateObjectStorage;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.vo.ContractReport;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL and business authorization; fixtures roll back and inference is never invoked. */
@SpringBootTest(properties = "scheduling.enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class ContractSupplementIntegrationTest {

    static final long TENANT = 910000291L,
        OTHER = 910000292L,
        USER = 910000491L,
        FOREIGN = 910000492L;
    static final String CONTRACT = "验收后付款。",
        MATERIAL = "合成验收记录：十月一日完成。",
        HASH = "f".repeat(64);
    final Identity actor = new Identity(USER, TENANT, 0, "Contract fixture"),
        other = new Identity(FOREIGN, OTHER, 0, "Other fixture");

    @Autowired
    JdbcTemplate db;

    @Autowired
    ContractReviewService contracts;

    @Autowired
    CaseService cases;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskService tasks;

    @MockitoBean
    PrivateObjectStorage storage;

    long caseId, contractVersion;
    Source material;

    record Source(long file, long version, long parse, String text, String sha, String key) {}

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry p) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        p.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(key));
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
        db.update(
            "INSERT INTO tenant(id,name) VALUES(?,'contract-supplement-only'),(?,'contract-supplement-other')",
            TENANT,
            OTHER
        );
        for (long user : List.of(USER, FOREIGN)) {
            long tenant = user == USER ? TENANT : OTHER;
            assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, user)
            );
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,'unused','Contract fixture')",
                user,
                "contract-supplement-" + user
            );
            db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
            db.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Contract fixture')",
                user,
                tenant
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
            cases.create(actor, "CONTRACT-" + uuid(), "Synthetic contract", "CIVIL", "").id()
        );
        configs.save(
            TENANT,
            "LLM",
            "test",
            "https://models.example.test/v1",
            "contract-test",
            "synthetic-contract-key"
        );
        configs.save(
            TENANT,
            "EMBEDDING",
            "test",
            "https://models.example.test/v1",
            "embedding-test",
            "synthetic-embedding-key"
        );
        contractVersion = source(caseId, "contract.txt", CONTRACT, HASH).version();
        material = source(caseId, "acceptance.txt", MATERIAL, "a".repeat(64));
    }

    String uuid() {
        return UUID.randomUUID().toString();
    }

    Source source(long parent, String name, String text, String hash) {
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?, ?,?,'AVAILABLE',?)",
            TENANT,
            parent,
            name,
            USER
        );
        long file = db.queryForObject(
            "SELECT MAX(id) FROM file_object WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        String key = "synthetic-contract-" + file;
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?,?,20,'text/plain')",
            TENANT,
            file,
            key,
            hash
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,?,'SUCCEEDED',?)",
            TENANT,
            parent,
            version,
            uuid(),
            USER
        );
        long parse = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND resource_version_id=?",
            Long.class,
            TENANT,
            version
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
        return new Source(file, version, parse, text, hash, key);
    }

    ContractReport report(
        List<ContractReport.Evidence> evidence,
        List<ContractReport.BasisConflict> conflicts
    ) {
        return new ContractReport(
            HASH,
            CONTRACT,
            "服务合同",
            List.of(new ContractReport.Clause("1", "付款", CONTRACT, 0, CONTRACT.length())),
            List.of(
                new ContractReport.Risk(
                    "1",
                    "LOW",
                    "期限需核对",
                    "明确期限",
                    evidence.stream().map(ContractReport.Evidence::id).toList()
                )
            ),
            List.of("验收说明"),
            evidence,
            true,
            conflicts
        );
    }

    long waiting(Long base, ContractReport report) {
        long id = Long.parseLong(
            contracts
                .request(actor, caseId, contractVersion, "NEUTRAL", "付款", base, uuid())
                .taskId()
        );
        var input = contracts.begin(id, 0, 1);
        assertEquals(2, input.stateVersion());
        assertTrue(contracts.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report));
        return id;
    }

    long waiting() {
        return waiting(null, report(List.of(), List.of()));
    }

    int count(String table, long id) {
        return db.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND task_id=?",
            Integer.class,
            TENANT,
            id
        );
    }

    void conflict(Runnable action) {
        assertEquals(409, assertThrows(BusinessException.class, action::run).getCode());
    }

    @Test
    void supplementIsFeeConfirmedCasBoundIdempotentAndKeepsImmutablePriorReport() {
        long id = waiting();
        var old = contracts.view(actor, id);
        String request = uuid();
        int events = count("outbox_event", id);
        assertEquals(
            400,
            assertThrows(BusinessException.class, () ->
                contracts.supplement(
                    actor,
                    id,
                    old.stateVersion(),
                    request,
                    List.of(material.parse()),
                    "说明",
                    false
                )
            ).getCode()
        );
        var next = contracts.supplement(
            actor,
            id,
            old.stateVersion(),
            request,
            List.of(material.parse()),
            "说明",
            true
        );
        assertEquals("QUEUED", next.status());
        assertEquals(old.stateVersion() + 1, next.stateVersion());
        assertEquals(events + 1, count("outbox_event", id));
        assertEquals(
            next,
            contracts.supplement(
                actor,
                id,
                old.stateVersion(),
                request,
                List.of(material.parse()),
                "说明",
                true
            )
        );
        assertEquals(events + 1, count("outbox_event", id));
        conflict(() ->
            contracts.supplement(
                actor,
                id,
                old.stateVersion(),
                request,
                List.of(material.parse()),
                "改变",
                true
            )
        );
        conflict(() ->
            contracts.supplement(
                actor,
                id,
                old.stateVersion(),
                uuid(),
                List.of(material.parse()),
                "",
                true
            )
        );
        assertEquals(1, count("contract_review_round", id));
        assertEquals(1, count("contract_review_supplement", id));
        var input = contracts.begin(id, 0, next.stateVersion());
        assertEquals(1, input.round());
        assertEquals(old.report(), input.previousReport());
        assertEquals(MATERIAL, input.supplements().getFirst().text());
        assertEquals(
            Long.toString(material.version()),
            input.supplements().getFirst().fileVersionId()
        );
        assertEquals(material.sha(), input.supplements().getFirst().sha256());
        assertEquals(1, input.supplements().getFirst().segments().getFirst().index());
        assertFalse(contracts.apply(TENANT, id, 0, 2, "WAITING_HUMAN", old.report()));
        assertTrue(
            contracts.apply(TENANT, id, 0, input.stateVersion(), "WAITING_HUMAN", old.report())
        );
        assertEquals(1, contracts.view(actor, id).round());
        assertEquals(
            old.report(),
            jsonRead(
                db.queryForObject(
                    "SELECT CAST(report_json AS CHAR) FROM contract_review_round WHERE tenant_id=? AND task_id=?",
                    String.class,
                    TENANT,
                    id
                )
            )
        );
    }

    ContractReport jsonRead(String value) {
        try {
            return json.readValue(value, ContractReport.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void onlySameCaseSucceededDigestMatchedLocatedMaterialsMayBeFixed() {
        long id = waiting();
        int version = contracts.view(actor, id).stateVersion();
        assertThrows(AccessDenied.class, () ->
            contracts.supplement(other, id, version, uuid(), List.of(material.parse()), "", true)
        );
        long otherCase = Long.parseLong(
            cases.create(actor, "OTHER-" + uuid(), "Other case", "CIVIL", "").id()
        );
        var foreign = source(otherCase, "wrong.txt", MATERIAL, material.sha());
        assertThrows(AccessDenied.class, () ->
            contracts.supplement(actor, id, version, uuid(), List.of(foreign.parse()), "", true)
        );
        db.update(
            "UPDATE async_task SET status='FAILED' WHERE tenant_id=? AND id=?",
            TENANT,
            material.parse()
        );
        assertThrows(AccessDenied.class, () ->
            contracts.supplement(actor, id, version, uuid(), List.of(material.parse()), "", true)
        );
        db.update(
            "UPDATE async_task SET status='SUCCEEDED' WHERE tenant_id=? AND id=?",
            TENANT,
            material.parse()
        );
        db.update(
            "UPDATE task_parse_result SET segments_json=NULL WHERE tenant_id=? AND task_id=?",
            TENANT,
            material.parse()
        );
        assertThrows(BusinessException.class, () ->
            contracts.supplement(actor, id, version, uuid(), List.of(material.parse()), "", true)
        );
        assertEquals(0, count("contract_review_round", id));
        assertEquals(version, contracts.view(actor, id).stateVersion());
    }

    @Test
    void colleagueMayUseReadableCaseMaterialWithoutAccessToAnotherCreatorsPrivateTask() {
        long peer = 910000493L;
        assertEquals(
            0,
            db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, peer)
        );
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'contract-colleague-only','unused','Colleague')",
            peer
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", TENANT, peer);
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'LAWYER','Colleague')",
            peer,
            TENANT
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            TENANT,
            peer,
            peer
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            TENANT,
            peer
        );
        cases.addMember(actor, caseId, peer, "EDITOR");
        var colleague = new Identity(peer, TENANT, 0, "Colleague");
        assertThrows(AccessDenied.class, () -> tasks.get(colleague, material.parse()));
        long id = Long.parseLong(
            contracts
                .request(colleague, caseId, contractVersion, "NEUTRAL", "", null, uuid())
                .taskId()
        );
        contracts.begin(id, 0, 1);
        contracts.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report(List.of(), List.of()));
        assertTrue(
            contracts
                .candidates(colleague, id)
                .stream()
                .anyMatch(c -> c.parseTaskId().equals(Long.toString(material.parse())))
        );
        var next = contracts.supplement(
            colleague,
            id,
            3,
            uuid(),
            List.of(material.parse()),
            "团队共享已解析材料",
            true
        );
        assertEquals(
            material.sha(),
            contracts.begin(id, 0, next.stateVersion()).supplements().getFirst().sha256()
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            material.file()
        );
        assertThrows(AccessDenied.class, () -> contracts.input(id, 0, next.stateVersion() + 1));
    }

    @Test
    void fixedMaterialDeletionRevocationAndTextMutationDenyReadResumeAndPublication() {
        long id = waiting();
        var next = contracts.supplement(actor, id, 3, uuid(), List.of(material.parse()), "", true);
        var input = contracts.begin(id, 0, next.stateVersion());
        db.update(
            "UPDATE task_parse_result SET content_text='changed' WHERE tenant_id=? AND task_id=?",
            TENANT,
            material.parse()
        );
        assertThrows(AccessDenied.class, () -> contracts.input(id, 0, input.stateVersion()));
        assertThrows(AccessDenied.class, () -> contracts.view(actor, id));
        assertThrows(AccessDenied.class, () ->
            contracts.apply(
                TENANT,
                id,
                0,
                input.stateVersion(),
                "WAITING_HUMAN",
                input.previousReport()
            )
        );
        db.update(
            "UPDATE task_parse_result SET content_text=? WHERE tenant_id=? AND task_id=?",
            MATERIAL,
            TENANT,
            material.parse()
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            material.file()
        );
        assertThrows(AccessDenied.class, () -> contracts.recover(actor, id, input.stateVersion()));
        db.update(
            "UPDATE file_object SET status='AVAILABLE' WHERE tenant_id=? AND id=?",
            TENANT,
            material.file()
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code='ai:review'",
            TENANT,
            USER
        );
        assertThrows(AccessDenied.class, () -> contracts.input(id, 0, input.stateVersion()));
    }

    @Test
    void uncertainCannotSupplementAndCancellationRejectsLateRoundResults() {
        long id = Long.parseLong(
            contracts.request(actor, caseId, contractVersion, "NEUTRAL", "", null, uuid()).taskId()
        );
        contracts.begin(id, 0, 1);
        assertTrue(contracts.apply(TENANT, id, 0, 2, "UNCERTAIN", null));
        conflict(() ->
            contracts.supplement(actor, id, 3, uuid(), List.of(material.parse()), "", true)
        );
        long next = waiting();
        contracts.supplement(actor, next, 3, uuid(), List.of(material.parse()), "", true);
        var input = contracts.begin(next, 0, 4);
        tasks.requestCancel(actor, next);
        assertFalse(
            contracts.apply(
                TENANT,
                next,
                0,
                input.stateVersion(),
                "WAITING_HUMAN",
                input.previousReport()
            )
        );
    }

    @Test
    void lowRiskBasisConflictsMustHaveTwoLiveReferencesAndRequireHuman() {
        var c = new ContractReport.BasisConflict("两个指引期限冲突", List.of("17", "18"));
        var evidence = List.of(
            new ContractReport.Evidence("17", "指引A"),
            new ContractReport.Evidence("18", "指引B")
        );
        var safe = report(evidence, List.of(c));
        safe.validate();
        assertThrows(IllegalArgumentException.class, () ->
            new ContractReport(
                HASH,
                CONTRACT,
                safe.contractType(),
                safe.clauses(),
                safe.risks(),
                List.of(),
                evidence,
                false,
                List.of(c)
            ).validate()
        );
        assertThrows(IllegalArgumentException.class, () ->
            report(
                evidence,
                List.of(new ContractReport.BasisConflict("假冲突", List.of("17", "999")))
            ).validate()
        );
    }

    @Test
    void citationLocatesAuthorizedPageAndDownloadsTheReferencedVersionRatherThanLatest()
        throws Exception {
        long base = Long.parseLong(knowledge.create(actor, caseId, "Synthetic references").id());
        db.update(
            "INSERT INTO knowledge_document(tenant_id,knowledge_base_id,file_version_id,parse_task_id,source_sha256,status,chunk_count,created_by,collection_name,embedding_dimension,model_identity) VALUES(?,?,?,?,?,'ACTIVE',1,?,'lexflow_chunks_synthetic_contract',4,?)",
            TENANT,
            base,
            material.version(),
            material.parse(),
            material.sha(),
            USER,
            "[\"test\",\"https://models.example.test/v1\",\"embedding-test\"]"
        );
        long document = db.queryForObject(
            "SELECT id FROM knowledge_document WHERE tenant_id=? AND file_version_id=?",
            Long.class,
            TENANT,
            material.version()
        );
        db.update(
            "INSERT INTO knowledge_chunk(tenant_id,knowledge_document_id,index_version,chunk_no,locator_kind,locator_index,start_offset,end_offset,content_text) VALUES(?,?,1,1,'PAGE',2,0,?,?)",
            TENANT,
            document,
            MATERIAL.length(),
            MATERIAL
        );
        long chunk = db.queryForObject(
            "SELECT id FROM knowledge_chunk WHERE tenant_id=? AND knowledge_document_id=?",
            Long.class,
            TENANT,
            document
        );
        long id = waiting(
            base,
            report(List.of(new ContractReport.Evidence(Long.toString(chunk), MATERIAL)), List.of())
        );
        var citation = contracts.citation(actor, id, chunk);
        assertEquals(material.file(), Long.parseLong(citation.fileId()));
        assertEquals(2, citation.locatorIndex());
        assertEquals("PAGE", citation.locatorKind());
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,2,'never-download-new-version',?,20,'text/plain')",
            TENANT,
            material.file(),
            "b".repeat(64)
        );
        when(storage.open(material.key())).thenReturn(
            new ByteArrayInputStream(MATERIAL.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        try (var stream = contracts.downloadCitation(actor, id, chunk).stream()) {
            assertEquals(
                MATERIAL,
                new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            );
        }
        verify(storage).open(material.key());
        verify(storage, never()).open("never-download-new-version");
        assertThrows(AccessDenied.class, () -> contracts.citation(other, id, chunk));
        assertThrows(AccessDenied.class, () -> contracts.citation(actor, id, chunk + 999));
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            material.file()
        );
        assertThrows(BusinessException.class, () -> contracts.citation(actor, id, chunk));
    }
}
