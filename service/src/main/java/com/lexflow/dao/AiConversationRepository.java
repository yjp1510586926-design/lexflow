package com.lexflow.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class AiConversationRepository {

    public record Conversation(
        long id,
        Long caseId,
        long baseId,
        long owner,
        String title,
        String createdAt,
        String scope
    ) {}

    public record Question(
        long id,
        String requestId,
        String question,
        String status,
        String answer,
        boolean insufficient,
        String evidenceJson,
        String citationIdsJson,
        String error,
        String createdAt
    ) {}

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert conversations;
    private final SimpleJdbcInsert questions;

    public AiConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        conversations = new SimpleJdbcInsert(jdbc)
            .withTableName("ai_conversation")
            .usingColumns(
                "tenant_id",
                "case_id",
                "knowledge_base_id",
                "created_by",
                "title",
                "scope"
            )
            .usingGeneratedKeyColumns("id");
        questions = new SimpleJdbcInsert(jdbc)
            .withTableName("ai_question")
            .usingColumns("tenant_id", "conversation_id", "request_id", "question")
            .usingGeneratedKeyColumns("id");
    }

    public long create(
        long tenant,
        Long caseId,
        long base,
        long owner,
        String title,
        String scope
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("tenant_id", tenant);
        values.put("case_id", caseId);
        values.put("knowledge_base_id", base);
        values.put("created_by", owner);
        values.put("title", title);
        values.put("scope", scope);
        return conversations.executeAndReturnKey(values).longValue();
    }

    public Conversation get(long tenant, long id, boolean lock) {
        return jdbc.query(
            "SELECT id,case_id,knowledge_base_id,created_by,title,created_at,scope FROM ai_conversation WHERE tenant_id=? AND id=?" +
                (lock ? " FOR UPDATE" : ""),
            resultSet -> resultSet.next() ? conversation(resultSet) : null,
            tenant,
            id
        );
    }

    public List<Conversation> list(long tenant, long base, long owner) {
        return jdbc.query(
            "SELECT id,case_id,knowledge_base_id,created_by,title,created_at,scope FROM ai_conversation WHERE tenant_id=? AND knowledge_base_id=? AND created_by=? ORDER BY updated_at DESC,id DESC LIMIT 50",
            (resultSet, rowNumber) -> conversation(resultSet),
            tenant,
            base,
            owner
        );
    }

    private static Conversation conversation(ResultSet resultSet) throws SQLException {
        return new Conversation(
            resultSet.getLong(1),
            resultSet.getObject(2, Long.class),
            resultSet.getLong(3),
            resultSet.getLong(4),
            resultSet.getString(5),
            resultSet.getString(6),
            resultSet.getString(7)
        );
    }

    public long begin(long tenant, long conversation, String requestId, String question) {
        long id = questions
            .executeAndReturnKey(
                Map.of(
                    "tenant_id",
                    tenant,
                    "conversation_id",
                    conversation,
                    "request_id",
                    requestId,
                    "question",
                    question
                )
            )
            .longValue();
        jdbc.update(
            "UPDATE ai_conversation SET updated_at=NOW(6) WHERE tenant_id=? AND id=?",
            tenant,
            conversation
        );
        return id;
    }

    public Question byRequest(long tenant, long conversation, String requestId) {
        return jdbc.query(
            "SELECT id,request_id,question,status,answer_text,insufficient_evidence,CAST(evidence_json AS CHAR),CAST(citation_ids_json AS CHAR),error_message,created_at FROM ai_question WHERE tenant_id=? AND conversation_id=? AND request_id=?",
            resultSet -> resultSet.next() ? row(resultSet) : null,
            tenant,
            conversation,
            requestId
        );
    }

    public List<Question> history(long tenant, long conversation) {
        return jdbc.query(
            "SELECT id,request_id,question,status,answer_text,insufficient_evidence,CAST(evidence_json AS CHAR),CAST(citation_ids_json AS CHAR),error_message,created_at FROM ai_question WHERE tenant_id=? AND conversation_id=? ORDER BY id LIMIT 100",
            (resultSet, rowNumber) -> row(resultSet),
            tenant,
            conversation
        );
    }

    private static Question row(ResultSet resultSet) throws SQLException {
        return new Question(
            resultSet.getLong(1),
            resultSet.getString(2),
            resultSet.getString(3),
            resultSet.getString(4),
            resultSet.getString(5),
            resultSet.getBoolean(6),
            resultSet.getString(7),
            resultSet.getString(8),
            resultSet.getString(9),
            resultSet.getString(10)
        );
    }

    public void expire(long tenant, long conversation) {
        jdbc.update(
            "UPDATE ai_question SET status='FAILED',error_message='上次请求已超时，请使用新的提问重新提交',finished_at=NOW(6) WHERE tenant_id=? AND conversation_id=? AND status='PROCESSING' AND created_at < TIMESTAMPADD(MINUTE,-5,NOW(6))",
            tenant,
            conversation
        );
    }

    public int finish(
        long tenant,
        long conversation,
        long question,
        String answer,
        boolean insufficient,
        String evidence,
        String ids
    ) {
        return jdbc.update(
            "UPDATE ai_question SET status='SUCCEEDED',answer_text=?,insufficient_evidence=?,evidence_json=?,citation_ids_json=?,finished_at=NOW(6) WHERE tenant_id=? AND conversation_id=? AND id=? AND status='PROCESSING' AND created_at >= TIMESTAMPADD(MINUTE,-5,NOW(6))",
            answer,
            insufficient,
            evidence,
            ids,
            tenant,
            conversation,
            question
        );
    }

    public void fail(long tenant, long conversation, long question, String error) {
        jdbc.update(
            "UPDATE ai_question SET status='FAILED',error_message=?,finished_at=NOW(6) WHERE tenant_id=? AND conversation_id=? AND id=? AND status='PROCESSING'",
            error,
            tenant,
            conversation,
            question
        );
    }
}
