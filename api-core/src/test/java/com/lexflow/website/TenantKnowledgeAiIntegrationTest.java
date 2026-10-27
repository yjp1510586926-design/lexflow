package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.AiConversationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.ContractReviewService;
import com.lexflow.service.KnowledgeService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.vo.ContractReport;
import com.lexflow.website.biz.AiConversationBiz;
import com.lexflow.website.biz.KnowledgeSearchBiz;
import com.lexflow.website.core.client.LegalQuestionClient;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** Real local MySQL scope/publication checks. Retrieval/inference are replaced; no paid requests. */
@SpringBootTest(properties = "scheduling.enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class TenantKnowledgeAiIntegrationTest {

    static final long TENANT = 910000030L,
        READER = 910000130L,
        OTHER = 910000031L;
    final Identity admin = new Identity(TENANT, TENANT, 0, "Admin");
    final Identity reader = new Identity(READER, TENANT, 0, "Reader");
    final Identity outsider = new Identity(OTHER, OTHER, 0, "Other tenant");

    @Autowired
    JdbcTemplate db;

    @Autowired
    KnowledgeService knowledge;

    @Autowired
    CaseService cases;

    @Autowired
    AiConversationService sessions;

    @Autowired
    AiConversationBiz questions;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    ContractReviewService contracts;

    @MockitoBean
    KnowledgeSearchBiz search;

    @MockitoBean
    LegalQuestionClient model;

    long base, caseId, contractVersion;
    Source publicSource;
    static final String TEXT = "办案指引：验收后十日内付款。",
        HASH = "c".repeat(64),
        CONTRACT = "验收后付款。";

    record Source(long file, long version, long document, long chunk) {}

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry properties) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        properties.add("LEXFLOW_AI_MASTER_KEY", () -> Base64.getEncoder().encodeToString(key));
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
            "INSERT INTO tenant(id,name) VALUES(?,'public-ai-fixture'),(?,'public-ai-other')",
            TENANT,
            OTHER
        );
        for (long user : List.of(TENANT, READER, OTHER)) {
            assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, user)
            );
            db.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,?,'unused','Public AI Tester')",
                user,
                "public-ai-" + user
            );
            db.update(
                "INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)",
                user == OTHER ? OTHER : TENANT,
                user
            );
        }
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Admin'),(?,?,'LAWYER','Reader')",
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
        for (String permission : List.of("knowledge:read", "ai:query"))
            db.update(
                "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,?)",
                TENANT,
                READER,
                permission
            );
        base = Long.parseLong(knowledge.createTenant(admin, "公共办案指引").id());
        configs.save(TENANT, "LLM", "fixture", "https://example.test/v1", "llm", "fake-only");
        configs.save(
            TENANT,
            "EMBEDDING",
            "fixture",
            "https://example.test/v1",
            "embedding",
            "fake-only"
        );
        publicSource = source(base, null);
        caseId = Long.parseLong(
            cases.create(admin, "PUBLIC-AI", "Contract case", "CIVIL", "").id()
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'contract.txt','AVAILABLE',?)",
            TENANT,
            caseId,
            TENANT
        );
        long file = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=? AND case_id=?",
            Long.class,
            TENANT,
            caseId
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,'synthetic-contract-only',?,30,'text/plain')",
            TENANT,
            file,
            HASH
        );
        contractVersion = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=? AND file_id=?",
            Long.class,
            TENANT,
            file
        );
        when(search.searchForQuestion(any(), eq(base), anyString(), anyBoolean())).thenReturn(
            found(publicSource)
        );
        when(model.answer(any(), anyString(), anyList(), anyList())).thenReturn(
            new LegalQuestionClient.Answer("验收后十日内付款。[1]", List.of("1"), false)
        );
    }

    Source source(long baseId, Long sourceCase) {
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,scope,knowledge_base_id,display_name,status,created_by) VALUES(?,?,?,?,?,'AVAILABLE',?)",
            TENANT,
            sourceCase,
            sourceCase == null ? "TENANT" : "CASE",
            sourceCase == null ? baseId : null,
            "guide-" + baseId + ".txt",
            TENANT
        );
        long file = db.queryForObject(
            "SELECT MAX(id) FROM file_object WHERE tenant_id=?",
            Long.class,
            TENANT
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,?, ?,30,'text/plain')",
            TENANT,
            file,
            "synthetic-guide-" + file,
            HASH
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
            sourceCase,
            version,
            "public-ai-parse-" + file,
            TENANT
        );
        long parse = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=? AND resource_version_id=?",
            Long.class,
            TENANT,
            version
        );
        db.update(
            "INSERT INTO knowledge_document(tenant_id,knowledge_base_id,file_version_id,parse_task_id,source_sha256,status,chunk_count,created_by,collection_name,embedding_dimension,model_identity) VALUES(?,?,?,?,?,'ACTIVE',1,?,'lexflow_chunks_public_fixture',4,?)",
            TENANT,
            baseId,
            version,
            parse,
            HASH,
            TENANT,
            "[\"fixture\",\"https://example.test/v1\",\"embedding\"]"
        );
        long document = db.queryForObject(
            "SELECT id FROM knowledge_document WHERE tenant_id=? AND file_version_id=?",
            Long.class,
            TENANT,
            version
        );
        db.update(
            "INSERT INTO knowledge_chunk(tenant_id,knowledge_document_id,index_version,chunk_no,locator_kind,locator_index,start_offset,end_offset,content_text) VALUES(?,?,1,1,'PARAGRAPH',1,0,?,?)",
            TENANT,
            document,
            TEXT.length(),
            TEXT
        );
        long chunk = db.queryForObject(
            "SELECT id FROM knowledge_chunk WHERE tenant_id=? AND knowledge_document_id=?",
            Long.class,
            TENANT,
            document
        );
        return new Source(file, version, document, chunk);
    }

    KnowledgeSearchBiz.SearchResult found(Source source) {
        return new KnowledgeSearchBiz.SearchResult(
            List.of(
                new KnowledgeSearchBiz.Citation(
                    Long.toString(source.chunk()),
                    Long.toString(source.document()),
                    Long.toString(source.file()),
                    Long.toString(source.version()),
                    "guide.txt",
                    1,
                    "PARAGRAPH",
                    1,
                    0,
                    TEXT.length(),
                    TEXT,
                    0.9
                )
            ),
            0
        );
    }

    long session() {
        return Long.parseLong(sessions.create(reader, base, null).id());
    }

    String request() {
        return UUID.randomUUID().toString();
    }

    long contract() {
        return Long.parseLong(
            contracts
                .request(admin, caseId, contractVersion, "NEUTRAL", "付款", base, request())
                .taskId()
        );
    }

    ContractReport report(boolean human) {
        return new ContractReport(
            HASH,
            CONTRACT,
            "服务合同",
            List.of(new ContractReport.Clause("1", "付款", CONTRACT, 0, CONTRACT.length())),
            List.of(
                new ContractReport.Risk(
                    "1",
                    human ? "HIGH" : "LOW",
                    "需核对期限",
                    "明确付款日期",
                    List.of(Long.toString(publicSource.chunk()))
                )
            ),
            List.of(),
            List.of(new ContractReport.Evidence(Long.toString(publicSource.chunk()), TEXT)),
            human
        );
    }

    void revoke(long role, String permission) {
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND role_id=? AND permission_code=?",
            TENANT,
            role,
            permission
        );
    }

    @Test
    void publicSessionsArePrivateEvenFromAdminAndNeedNoCaseOrFilePermission() {
        var session = sessions.create(reader, base, null);
        assertNull(session.caseId());
        assertEquals("TENANT", session.scope());
        assertEquals("律所资料问答", session.title());
        assertEquals(1, sessions.list(reader, base).size());
        assertTrue(sessions.list(admin, base).isEmpty());
        assertThrows(AccessDenied.class, () ->
            sessions.history(admin, Long.parseLong(session.id()))
        );
        assertThrows(AccessDenied.class, () ->
            sessions.history(outsider, Long.parseLong(session.id()))
        );
        assertThrows(AccessDenied.class, () -> sessions.create(outsider, base, null));
        assertThrows(DataAccessException.class, () ->
            db.update("UPDATE ai_conversation SET scope='CASE' WHERE tenant_id=?", TENANT)
        );
        assertThrows(DataAccessException.class, () ->
            db.update("UPDATE ai_conversation SET case_id=? WHERE tenant_id=?", caseId, TENANT)
        );
    }

    @Test
    void publicAnswerIsIdempotentAndDeletedSourcesHideItsHistory() {
        long id = session();
        String key = request();
        var answer = questions.ask(reader, id, key, "何时付款？");
        assertEquals(TEXT, answer.citations().getFirst().text());
        assertEquals(answer.id(), questions.ask(reader, id, key, "何时付款？").id());
        verify(model, times(1)).answer(any(), anyString(), anyList(), anyList());
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            publicSource.file()
        );
        var hidden = sessions.history(reader, id).getFirst();
        assertTrue(hidden.sourcesUnavailable());
        assertNull(hidden.answer());
        assertTrue(hidden.citations().isEmpty());
    }

    @Test
    void publicQueryRevocationBeforeModelOrDuringInferencePreventsPublication() {
        long id = session();
        revoke(READER, "knowledge:read");
        assertThrows(AccessDenied.class, () -> questions.ask(reader, id, request(), "何时付款？"));
        verifyNoInteractions(search, model);
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES(?,?,'knowledge:read')",
            TENANT,
            READER
        );
        when(model.answer(any(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            revoke(READER, "ai:query");
            return new LegalQuestionClient.Answer("十日内付款。[1]", List.of("1"), false);
        });
        assertThrows(AccessDenied.class, () -> questions.ask(reader, id, request(), "何时付款？"));
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM ai_question WHERE tenant_id=?",
                String.class,
                TENANT
            )
        );
        assertNull(
            db.queryForObject(
                "SELECT answer_text FROM ai_question WHERE tenant_id=?",
                String.class,
                TENANT
            )
        );
        assertThrows(AccessDenied.class, () -> sessions.history(reader, id));
    }

    @Test
    void caseExcerptCannotBeDisclosedThroughPublicQuestionEvenWhenRetrieverReturnsIt() {
        long privateBase = Long.parseLong(knowledge.create(admin, caseId, "Private").id());
        when(search.searchForQuestion(any(), eq(base), anyString(), anyBoolean())).thenReturn(
            found(source(privateBase, caseId))
        );
        assertThrows(BusinessException.class, () ->
            questions.ask(reader, session(), request(), "何时付款？")
        );
        verifyNoInteractions(model);
    }

    @Test
    void emptyPublicEvidenceExpandsOnceAndUsesPublicInsufficiencyMessage() {
        when(search.searchForQuestion(any(), eq(base), anyString(), anyBoolean())).thenReturn(
            new KnowledgeSearchBiz.SearchResult(List.of(), 0)
        );
        var answer = questions.ask(reader, session(), request(), "哪些法律适用？");
        assertTrue(answer.insufficientEvidence());
        assertEquals(AiConversationBiz.TENANT_INSUFFICIENT, answer.answer());
        verifyNoInteractions(model);
        verify(search, times(1)).searchForQuestion(any(), eq(base), anyString(), eq(false));
        verify(search, times(1)).searchForQuestion(any(), eq(base), anyString(), eq(true));
    }

    @Test
    void missingLlmKeyCannotStartPublicRetrievalAndArchivedBaseDeniesHistory() {
        long id = session();
        configs.delete(TENANT, "LLM");
        assertThrows(BusinessException.class, () ->
            questions.ask(reader, id, request(), "何时付款？")
        );
        verifyNoInteractions(search, model);
        db.update(
            "UPDATE knowledge_base SET status='ARCHIVED' WHERE tenant_id=? AND id=?",
            TENANT,
            base
        );
        assertThrows(AccessDenied.class, () -> sessions.history(reader, id));
    }

    @Test
    void contractCanPublishAndReadAuthorizedPublicEvidence() {
        long id = contract();
        var input = contracts.begin(id, 0, 1);
        assertEquals(Long.toString(base), input.knowledgeBaseId());
        assertTrue(contracts.apply(TENANT, id, 0, 2, "SUCCEEDED", report(false)));
        assertEquals(TEXT, contracts.view(admin, id).report().evidence().getFirst().text());
        revoke(TENANT, "knowledge:read");
        assertThrows(AccessDenied.class, () -> contracts.view(admin, id));
    }

    @Test
    void publicReferenceRevocationBlocksRecoveryInputAndLateContractCallback() {
        long id = contract();
        contracts.begin(id, 0, 1);
        revoke(TENANT, "knowledge:read");
        assertThrows(AccessDenied.class, () -> contracts.input(id, 0, 2));
        assertThrows(AccessDenied.class, () -> contracts.recover(admin, id, 2));
        assertThrows(AccessDenied.class, () ->
            contracts.apply(TENANT, id, 0, 2, "SUCCEEDED", report(false))
        );
        assertNull(
            db.queryForObject(
                "SELECT CAST(report_json AS CHAR) FROM contract_review WHERE tenant_id=? AND task_id=?",
                String.class,
                TENANT,
                id
            )
        );
        assertEquals(
            "RUNNING",
            db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                TENANT,
                id
            )
        );
    }

    @Test
    void publicSourceDeletionAndArchivedBaseBlockContractPublicationOrApproval() {
        long id = contract();
        contracts.begin(id, 0, 1);
        assertTrue(contracts.apply(TENANT, id, 0, 2, "WAITING_HUMAN", report(true)));
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            TENANT,
            publicSource.file()
        );
        assertThrows(BusinessException.class, () ->
            contracts.approve(admin, id, 3, request(), "已核对")
        );
        assertThrows(BusinessException.class, () -> contracts.view(admin, id));
        db.update(
            "UPDATE knowledge_base SET status='ARCHIVED' WHERE tenant_id=? AND id=?",
            TENANT,
            base
        );
        assertThrows(AccessDenied.class, () ->
            contracts.approve(admin, id, 3, request(), "已核对")
        );
    }

    @Test
    void contractCannotSelectAnotherCaseEvenWithAdminAccessToBoth() {
        long otherCase = Long.parseLong(
            cases.create(admin, "OTHER-CASE", "Other case", "CIVIL", "").id()
        );
        long privateBase = Long.parseLong(
            knowledge.create(admin, otherCase, "Other private base").id()
        );
        assertThrows(AccessDenied.class, () ->
            contracts.request(admin, caseId, contractVersion, "NEUTRAL", "", privateBase, request())
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM async_task WHERE tenant_id=? AND type='CONTRACT_REVIEW'",
                Integer.class,
                TENANT
            )
        );
    }

    @Test
    void choosingAnEmptyPublicBaseStillRequiresPermissionsBeforeHandingOutModelInput() {
        long empty = Long.parseLong(knowledge.createTenant(admin, "Empty public base").id());
        long id = Long.parseLong(
            contracts
                .request(admin, caseId, contractVersion, "NEUTRAL", "", empty, request())
                .taskId()
        );
        revoke(TENANT, "ai:query");
        assertThrows(AccessDenied.class, () -> contracts.begin(id, 0, 1));
        assertEquals(
            "QUEUED",
            db.queryForObject(
                "SELECT status FROM async_task WHERE tenant_id=? AND id=?",
                String.class,
                TENANT,
                id
            )
        );
    }
}
