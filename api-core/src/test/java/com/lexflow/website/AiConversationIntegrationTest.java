package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.AiConversationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.website.biz.AiConversationBiz;
import com.lexflow.website.biz.KnowledgeSearchBiz;
import com.lexflow.website.core.client.LegalQuestionClient;
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

/** Real MySQL ownership/publication checks; inference is explicitly replaced to avoid paid calls. */
@SpringBootTest(properties = "scheduling.enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LOCAL_INTEGRATION", matches = "true")
class AiConversationIntegrationTest {

    @Autowired
    JdbcTemplate db;

    @Autowired
    CaseService cases;

    @Autowired
    AiConfigurationService configs;

    @Autowired
    AiConversationService sessions;

    @Autowired
    AiConversationBiz questions;

    @MockitoBean
    KnowledgeSearchBiz search;

    @MockitoBean
    LegalQuestionClient model;

    final long tenant = 900000093L,
        user = 900000093L;
    final Identity actor = new Identity(user, tenant, 0, "Question Tester");
    long caseId, base, file, document, chunk;
    String session;
    String text = "付款条款：验收后付款。";

    @DynamicPropertySource
    static void key(DynamicPropertyRegistry registry) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        registry.add("LEXFLOW_AI_MASTER_KEY", () -> encoded);
    }

    @BeforeEach
    void fixtures() {
        db.update("INSERT INTO tenant(id,name) VALUES(?,'question-integration-only')", tenant);
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'question-integration-only','test-only-unused','Question Tester')",
            user
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, user);
        db.update(
            "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TENANT_ADMIN','Test Admin')",
            tenant,
            tenant
        );
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            tenant,
            user,
            tenant
        );
        db.update(
            "INSERT INTO role_permission(tenant_id,role_id,permission_code) SELECT ?,?,code FROM permission",
            tenant,
            tenant
        );
        caseId = Long.parseLong(
            cases.create(actor, "QUESTION-TEST", "Question test", "CIVIL", "").id()
        );
        db.update(
            "INSERT INTO file_object(tenant_id,case_id,display_name,status,created_by) VALUES(?,?,'question.txt','AVAILABLE',?)",
            tenant,
            caseId,
            user
        );
        file = db.queryForObject(
            "SELECT id FROM file_object WHERE tenant_id=?",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO file_version(tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,?,1,'question-test-only',?,30,'text/plain')",
            tenant,
            file,
            "d".repeat(64)
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=?",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO async_task(tenant_id,case_id,type,resource_version_id,idempotency_key,status,created_by) VALUES(?,?,'DOCUMENT_PARSE',?,'question-test-parse','SUCCEEDED',?)",
            tenant,
            caseId,
            version,
            user
        );
        long parse = db.queryForObject(
            "SELECT id FROM async_task WHERE tenant_id=?",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO knowledge_base(tenant_id,case_id,name,created_by) VALUES(?,?,'Question base',?)",
            tenant,
            caseId,
            user
        );
        base = db.queryForObject(
            "SELECT id FROM knowledge_base WHERE tenant_id=?",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO knowledge_document(tenant_id,knowledge_base_id,file_version_id,parse_task_id,source_sha256,status,chunk_count,created_by) VALUES(?,?,?,?,?,'ACTIVE',1,?)",
            tenant,
            base,
            version,
            parse,
            "d".repeat(64),
            user
        );
        document = db.queryForObject(
            "SELECT id FROM knowledge_document WHERE tenant_id=?",
            Long.class,
            tenant
        );
        db.update(
            "INSERT INTO knowledge_chunk(tenant_id,knowledge_document_id,index_version,chunk_no,locator_kind,locator_index,start_offset,end_offset,content_text) VALUES(?,?,1,1,'PARAGRAPH',1,0,?,?)",
            tenant,
            document,
            text.length(),
            text
        );
        chunk = db.queryForObject(
            "SELECT id FROM knowledge_chunk WHERE tenant_id=?",
            Long.class,
            tenant
        );
        session = sessions.create(actor, base, "付款问答").id();
        configs.save(
            tenant,
            "LLM",
            "fake",
            "https://models.example.test/v1",
            "fake-model",
            "fake-question-key"
        );
        var found = new KnowledgeSearchBiz.SearchResult(
            List.of(
                new KnowledgeSearchBiz.Citation(
                    Long.toString(chunk),
                    Long.toString(document),
                    Long.toString(file),
                    Long.toString(version),
                    "question.txt",
                    1,
                    "PARAGRAPH",
                    1,
                    0,
                    text.length(),
                    text,
                    0.9
                )
            ),
            0
        );
        when(search.searchForQuestion(any(), eq(base), anyString(), anyBoolean())).thenReturn(
            found
        );
        when(model.answer(any(), anyString(), anyList(), anyList())).thenReturn(
            new LegalQuestionClient.Answer("验收后付款。[1]", List.of("1"), false)
        );
    }

    @Test
    void persistentAnswerIsIdempotentAndHistoryRechecksDeletedSources() {
        String request = UUID.randomUUID().toString();
        var answer = questions.ask(actor, Long.parseLong(session), request, "何时付款？");
        assertEquals("SUCCEEDED", answer.status());
        assertEquals(text, answer.citations().getFirst().text());
        assertEquals(
            answer.id(),
            questions.ask(actor, Long.parseLong(session), request, "何时付款？").id()
        );
        verify(model, times(1)).answer(any(), anyString(), anyList(), anyList());
        assertThrows(BusinessException.class, () ->
            questions.ask(actor, Long.parseLong(session), request, "谁负责验收？")
        );
        db.update(
            "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
            tenant,
            file
        );
        var hidden = sessions.history(actor, Long.parseLong(session)).getFirst();
        assertTrue(hidden.sourcesUnavailable());
        assertNull(hidden.answer());
        assertTrue(hidden.citations().isEmpty());
        assertThrows(AccessDenied.class, () ->
            sessions.history(new Identity(user, tenant + 1, 0, "Other"), Long.parseLong(session))
        );
        db.update(
            "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='ai:query'",
            tenant
        );
        assertThrows(AccessDenied.class, () -> sessions.history(actor, Long.parseLong(session)));
    }

    @Test
    void sourceDeletionDuringModelCallCannotPublishAnswer() {
        when(model.answer(any(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            db.update(
                "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
                tenant,
                file
            );
            return new LegalQuestionClient.Answer("验收后付款。[1]", List.of("1"), false);
        });
        String request = UUID.randomUUID().toString();
        assertThrows(BusinessException.class, () ->
            questions.ask(actor, Long.parseLong(session), request, "何时付款？")
        );
        assertEquals("FAILED", sessions.question(actor, Long.parseLong(session), request).status());
        assertNull(
            db.queryForObject(
                "SELECT answer_text FROM ai_question WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
    }

    @Test
    void sameTenantColleagueCannotReadPrivateHistoryAndRevocationPreventsPublication() {
        long colleague = user + 1;
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(?,'question-colleague','unused','Colleague')",
            colleague
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(?,?)", tenant, colleague);
        db.update(
            "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES(?,?,?)",
            tenant,
            colleague,
            tenant
        );
        assertThrows(AccessDenied.class, () ->
            sessions.history(
                new Identity(colleague, tenant, 0, "Colleague"),
                Long.parseLong(session)
            )
        );
        when(model.answer(any(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            db.update(
                "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
                tenant
            );
            return new LegalQuestionClient.Answer("验收后付款。[1]", List.of("1"), false);
        });
        assertThrows(AccessDenied.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "何时付款？"
            )
        );
        assertEquals(
            "FAILED",
            db.queryForObject(
                "SELECT status FROM ai_question WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
        assertNull(
            db.queryForObject(
                "SELECT answer_text FROM ai_question WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
    }

    @Test
    void missingManualKeyOrUnknownReferencesCannotProduceAnswer() {
        configs.delete(tenant, "LLM");
        assertThrows(BusinessException.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "何时付款？"
            )
        );
        verifyNoInteractions(search, model);
        configs.save(
            tenant,
            "LLM",
            "fake",
            "https://models.example.test/v1",
            "fake-model",
            "fake-question-key"
        );
        when(model.answer(any(), anyString(), anyList(), anyList())).thenReturn(
            new LegalQuestionClient.Answer("伪造来源。[2]", List.of("2"), false)
        );
        String request = UUID.randomUUID().toString();
        assertThrows(BusinessException.class, () ->
            questions.ask(actor, Long.parseLong(session), request, "何时付款？")
        );
        assertEquals("FAILED", sessions.question(actor, Long.parseLong(session), request).status());
    }

    @Test
    void insufficientEvidenceDoesNotCallLlmAndInFlightDuplicateDoesNotRestart() {
        when(search.searchForQuestion(any(), eq(base), anyString(), anyBoolean())).thenReturn(
            new KnowledgeSearchBiz.SearchResult(List.of(), 0)
        );
        var answer = questions.ask(
            actor,
            Long.parseLong(session),
            UUID.randomUUID().toString(),
            "何时付款？"
        );
        assertTrue(answer.insufficientEvidence());
        assertEquals(AiConversationBiz.INSUFFICIENT, answer.answer());
        verifyNoInteractions(model);
        String request = UUID.randomUUID().toString();
        sessions.begin(actor, Long.parseLong(session), request, "谁负责验收？");
        assertEquals(
            "PROCESSING",
            questions.ask(actor, Long.parseLong(session), request, "谁负责验收？").status()
        );
        assertThrows(BusinessException.class, () ->
            sessions.begin(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "另一个问题？"
            )
        );
        db.update(
            "UPDATE ai_question SET created_at=TIMESTAMPADD(MINUTE,-6,NOW(6)) WHERE tenant_id=? AND request_id=?",
            tenant,
            request
        );
        assertEquals(
            "FAILED",
            questions.ask(actor, Long.parseLong(session), request, "谁负责验收？").status()
        );
        verifyNoInteractions(model);
    }

    private KnowledgeSearchBiz.SearchResult broaderEvidence() {
        String extra = "付款截止日期：2026 年 11 月 1 日。";
        db.update(
            "INSERT INTO knowledge_chunk(tenant_id,knowledge_document_id,index_version,chunk_no,locator_kind,locator_index,start_offset,end_offset,content_text) VALUES(?,?,1,2,'PARAGRAPH',2,0,?,?)",
            tenant,
            document,
            extra.length(),
            extra
        );
        long extraChunk = db.queryForObject(
            "SELECT id FROM knowledge_chunk WHERE tenant_id=? AND knowledge_document_id=? AND chunk_no=2",
            Long.class,
            tenant,
            document
        );
        long version = db.queryForObject(
            "SELECT id FROM file_version WHERE tenant_id=?",
            Long.class,
            tenant
        );
        return new KnowledgeSearchBiz.SearchResult(
            List.of(
                new KnowledgeSearchBiz.Citation(
                    Long.toString(chunk),
                    Long.toString(document),
                    Long.toString(file),
                    Long.toString(version),
                    "question.txt",
                    1,
                    "PARAGRAPH",
                    1,
                    0,
                    text.length(),
                    text,
                    0.9
                ),
                new KnowledgeSearchBiz.Citation(
                    Long.toString(extraChunk),
                    Long.toString(document),
                    Long.toString(file),
                    Long.toString(version),
                    "question.txt",
                    1,
                    "PARAGRAPH",
                    2,
                    0,
                    extra.length(),
                    extra,
                    0.8
                )
            ),
            0
        );
    }

    @Test
    void expandedEvidenceCanAnswerOnceAndDuplicateQuestionCannotRepeatEitherRound() {
        when(search.searchForQuestion(any(), eq(base), anyString(), eq(true))).thenReturn(
            broaderEvidence()
        );
        when(model.answer(any(), anyString(), anyList(), anyList())).thenReturn(
            new LegalQuestionClient.Answer("资料不足", List.of(), true),
            new LegalQuestionClient.Answer(
                "付款截止日期为 2026 年 11 月 1 日。[2]",
                List.of("2"),
                false
            )
        );
        String request = UUID.randomUUID().toString();
        var answer = questions.ask(actor, Long.parseLong(session), request, "付款截止日期？");
        assertFalse(answer.insufficientEvidence());
        assertEquals(1, answer.citations().size());
        assertEquals(2, answer.citations().getFirst().locatorIndex());
        assertEquals(
            answer.id(),
            questions.ask(actor, Long.parseLong(session), request, "付款截止日期？").id()
        );
        verify(model, times(2)).answer(any(), anyString(), anyList(), anyList());
        verify(search, times(1)).searchForQuestion(any(), eq(base), anyString(), eq(false));
        verify(search, times(1)).searchForQuestion(any(), eq(base), anyString(), eq(true));
    }

    @Test
    void twoInsufficientRoundsStopAndUnchangedEvidenceDoesNotCallLlmAgain() {
        var broader = broaderEvidence();
        when(model.answer(any(), anyString(), anyList(), anyList())).thenReturn(
            new LegalQuestionClient.Answer("资料不足", List.of(), true)
        );
        var unchanged = questions.ask(
            actor,
            Long.parseLong(session),
            UUID.randomUUID().toString(),
            "违约金多少？"
        );
        assertTrue(unchanged.insufficientEvidence());
        verify(model, times(1)).answer(any(), anyString(), anyList(), anyList());
        when(search.searchForQuestion(any(), eq(base), anyString(), eq(true))).thenReturn(broader);
        var stillInsufficient = questions.ask(
            actor,
            Long.parseLong(session),
            UUID.randomUUID().toString(),
            "谁承担律师费？"
        );
        assertTrue(stillInsufficient.insufficientEvidence());
        assertEquals(AiConversationBiz.INSUFFICIENT, stillInsufficient.answer());
        assertTrue(stillInsufficient.citations().isEmpty());
        verify(model, times(3)).answer(any(), anyString(), anyList(), anyList());
        verify(search, times(2)).searchForQuestion(any(), eq(base), anyString(), eq(true));
    }

    @Test
    void emptyFirstRoundCanUseBroaderEvidenceWithoutAnEmptyLlmCall() {
        when(search.searchForQuestion(any(), eq(base), anyString(), eq(false))).thenReturn(
            new KnowledgeSearchBiz.SearchResult(List.of(), 0)
        );
        var answer = questions.ask(
            actor,
            Long.parseLong(session),
            UUID.randomUUID().toString(),
            "何时付款？"
        );
        assertFalse(answer.insufficientEvidence());
        verify(model, times(1)).answer(any(), anyString(), anyList(), anyList());
        verify(search, times(1)).searchForQuestion(any(), eq(base), anyString(), eq(true));
    }

    @Test
    void providerFailureAndInvalidFirstReferencesNeverTriggerASecondCharge() {
        when(model.answer(any(), anyString(), anyList(), anyList())).thenThrow(
            BusinessException.conflict("问答暂不可用")
        );
        assertThrows(BusinessException.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "付款截止日期？"
            )
        );
        verify(search, never()).searchForQuestion(any(), eq(base), anyString(), eq(true));
        doReturn(new LegalQuestionClient.Answer("伪造来源。[99]", List.of("99"), false))
            .when(model)
            .answer(any(), anyString(), anyList(), anyList());
        assertThrows(BusinessException.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "谁承担律师费？"
            )
        );
        verify(search, never()).searchForQuestion(any(), eq(base), anyString(), eq(true));
        verify(model, times(2)).answer(any(), anyString(), anyList(), anyList());
    }

    @Test
    void deletionBeforeExpansionOrDuringBroaderSearchCannotDiscloseAnotherPrompt() {
        when(model.answer(any(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            db.update(
                "UPDATE file_object SET status='DELETED' WHERE tenant_id=? AND id=?",
                tenant,
                file
            );
            return new LegalQuestionClient.Answer("资料不足", List.of(), true);
        });
        assertThrows(BusinessException.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "付款截止日期？"
            )
        );
        verify(search, never()).searchForQuestion(any(), eq(base), anyString(), eq(true));
        db.update(
            "UPDATE file_object SET status='AVAILABLE' WHERE tenant_id=? AND id=?",
            tenant,
            file
        );
        var broader = broaderEvidence();
        doReturn(new LegalQuestionClient.Answer("资料不足", List.of(), true))
            .when(model)
            .answer(any(), anyString(), anyList(), anyList());
        when(search.searchForQuestion(any(), eq(base), anyString(), eq(true))).thenAnswer(call -> {
            db.update(
                "DELETE FROM role_permission WHERE tenant_id=? AND permission_code='file:read'",
                tenant
            );
            return broader;
        });
        assertThrows(AccessDenied.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "谁承担律师费？"
            )
        );
        verify(model, times(2)).answer(any(), anyString(), anyList(), anyList());
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_question WHERE tenant_id=? AND answer_text IS NOT NULL",
                Integer.class,
                tenant
            )
        );
    }

    @Test
    void modelReplacementBeforeBroaderInferenceCannotUseAnOldKey() {
        var broader = broaderEvidence();
        when(model.answer(any(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            configs.save(
                tenant,
                "LLM",
                "fake",
                "https://models.example.test/v1",
                "fake-model-v2",
                "replacement-key"
            );
            return new LegalQuestionClient.Answer("资料不足", List.of(), true);
        });
        when(search.searchForQuestion(any(), eq(base), anyString(), eq(true))).thenReturn(broader);
        assertThrows(BusinessException.class, () ->
            questions.ask(
                actor,
                Long.parseLong(session),
                UUID.randomUUID().toString(),
                "付款截止日期？"
            )
        );
        verify(model, times(1)).answer(any(), anyString(), anyList(), anyList());
        assertNull(
            db.queryForObject(
                "SELECT answer_text FROM ai_question WHERE tenant_id=?",
                String.class,
                tenant
            )
        );
    }
}
