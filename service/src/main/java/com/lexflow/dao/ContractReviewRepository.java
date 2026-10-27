package com.lexflow.dao;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContractReviewRepository {

    public record Review(
        String stance,
        String focus,
        Long baseId,
        String modelIdentity,
        String reportJson,
        String waitingReason,
        String decisionId,
        Long decidedBy,
        String decisionNote,
        int round,
        int executionProtocol
    ) {}

    public record Round(
        int number,
        String requestId,
        int fromStateVersion,
        long createdBy,
        String instructions,
        String reportJson
    ) {}

    public record Supplement(
        int round,
        long parseTaskId,
        long fileVersionId,
        String sha256,
        String fileName,
        String text,
        String segmentsJson
    ) {}

    public record Candidate(
        String parseTaskId,
        String fileVersionId,
        String fileName,
        int fileVersion
    ) {}

    private final JdbcTemplate db;

    public ContractReviewRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Review get(long tenant, long task) {
        return db.query(
            """
            SELECT stance,focus_text,knowledge_base_id,model_identity,CAST(report_json AS CHAR),
                   waiting_reason,decision_id,decided_by,decision_note,review_round,execution_protocol
            FROM contract_review WHERE tenant_id=? AND task_id=?
            """,
            resultSet ->
                resultSet.next()
                    ? new Review(
                          resultSet.getString(1),
                          resultSet.getString(2),
                          (Long) resultSet.getObject(3),
                          resultSet.getString(4),
                          resultSet.getString(5),
                          resultSet.getString(6),
                          resultSet.getString(7),
                          (Long) resultSet.getObject(8),
                          resultSet.getString(9),
                          resultSet.getInt(10),
                          resultSet.getInt(11)
                      )
                    : null,
            tenant,
            task
        );
    }

    public List<Round> rounds(long tenant, long task) {
        return db.query(
            "SELECT round_no,request_id,from_state_version,created_by,instructions,CAST(report_json AS CHAR) FROM contract_review_round WHERE tenant_id=? AND task_id=? ORDER BY round_no",
            (resultSet, rowNumber) ->
                new Round(
                    resultSet.getInt(1),
                    resultSet.getString(2),
                    resultSet.getInt(3),
                    resultSet.getLong(4),
                    resultSet.getString(5),
                    resultSet.getString(6)
                ),
            tenant,
            task
        );
    }

    public List<Supplement> supplements(long tenant, long task) {
        return db.query(
            "SELECT round_no,parse_task_id,file_version_id,source_sha256,display_name,source_text,CAST(segments_json AS CHAR) FROM contract_review_supplement WHERE tenant_id=? AND task_id=? ORDER BY round_no,parse_task_id",
            (resultSet, rowNumber) ->
                new Supplement(
                    resultSet.getInt(1),
                    resultSet.getLong(2),
                    resultSet.getLong(3),
                    resultSet.getString(4),
                    resultSet.getString(5),
                    resultSet.getString(6),
                    resultSet.getString(7)
                ),
            tenant,
            task
        );
    }

    public List<Candidate> candidates(long tenant, long caseId) {
        return db.query(
            """
            SELECT t.id,v.id,f.display_name,v.version_no FROM async_task t
            JOIN task_parse_result r ON r.tenant_id=t.tenant_id AND r.task_id=t.id
            JOIN file_version v ON v.tenant_id=t.tenant_id AND v.id=t.resource_version_id AND v.sha256=r.source_sha256
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
              AND f.case_id=t.case_id AND f.scope='CASE' AND f.knowledge_base_id IS NULL AND f.status='AVAILABLE'
            WHERE t.tenant_id=? AND t.case_id=? AND t.type='DOCUMENT_PARSE' AND t.status='SUCCEEDED'
              AND r.segments_json IS NOT NULL AND CHAR_LENGTH(r.content_text) BETWEEN 1 AND 40000
            ORDER BY t.id DESC LIMIT 100
            """,
            (resultSet, rowNumber) ->
                new Candidate(
                    Long.toString(resultSet.getLong(1)),
                    Long.toString(resultSet.getLong(2)),
                    resultSet.getString(3),
                    resultSet.getInt(4)
                ),
            tenant,
            caseId
        );
    }

    public void addRound(
        long tenant,
        long task,
        int round,
        String requestId,
        int version,
        long actor,
        String instructions,
        String report
    ) {
        db.update(
            "INSERT INTO contract_review_round(tenant_id,task_id,round_no,request_id,from_state_version,created_by,instructions,report_json) VALUES(?,?,?,?,?,?,?,?)",
            tenant,
            task,
            round,
            requestId,
            version,
            actor,
            instructions,
            report
        );
        if (
            db.update(
                "UPDATE contract_review SET review_round=?,report_json=NULL,waiting_reason=NULL,decision_id=NULL,decided_by=NULL,decision_note=NULL,decided_at=NULL WHERE tenant_id=? AND task_id=? AND review_round=?",
                round,
                tenant,
                task,
                round - 1
            ) != 1
        ) {
            throw new IllegalStateException("合同补材轮次已变化");
        }
    }

    public void addSupplement(
        long tenant,
        long task,
        int round,
        KnowledgeRepository.SourceRow source
    ) {
        db.update(
            "INSERT INTO contract_review_supplement(tenant_id,task_id,round_no,parse_task_id,file_version_id,source_sha256,display_name,source_text,segments_json) VALUES(?,?,?,?,?,?,?,?,?)",
            tenant,
            task,
            round,
            source.taskId(),
            source.fileVersionId(),
            source.sha256(),
            source.fileName(),
            source.text(),
            source.segmentsJson()
        );
    }

    public void create(
        long tenant,
        long task,
        String stance,
        String focus,
        Long base,
        String model
    ) {
        db.update(
            "INSERT INTO contract_review(tenant_id,task_id,stance,focus_text,knowledge_base_id,model_identity,execution_protocol) VALUES(?,?,?,?,?,?,2)",
            tenant,
            task,
            stance,
            focus,
            base,
            model
        );
    }

    public boolean currentDead(long tenant, long task, int attempt, int version) {
        return Integer.valueOf(1).equals(
            db.queryForObject(
                "SELECT COUNT(*) FROM task_delivery_recovery WHERE tenant_id=? AND task_id=? AND attempt=? AND registered_state_version=? AND delivery_status='DEAD'",
                Integer.class,
                tenant,
                task,
                attempt,
                version
            )
        );
    }

    public void deliveryReason(long tenant, long task, String reason) {
        if (
            db.update(
                """
                UPDATE contract_review r JOIN async_task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id
                SET r.waiting_reason=? WHERE r.tenant_id=? AND r.task_id=? AND t.type='CONTRACT_REVIEW' AND t.status=?
                """,
                reason,
                tenant,
                task,
                reason == null ? "QUEUED" : "WAITING_HUMAN"
            ) != 1
        ) {
            throw new IllegalStateException("审查投递状态已变化");
        }
    }

    public void result(long tenant, long task, String report, String reason) {
        if (
            db.update(
                "UPDATE contract_review SET report_json=?,waiting_reason=? WHERE tenant_id=? AND task_id=?",
                report,
                reason,
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("合同审查任务缺失");
        }
    }

    public void decision(long tenant, long task, String id, long actor, String note) {
        if (
            db.update(
                """
                UPDATE contract_review SET decision_id=?,decided_by=?,decision_note=?,decided_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND task_id=? AND decision_id IS NULL AND report_json IS NOT NULL AND waiting_reason='REVIEW'
                """,
                id,
                actor,
                note,
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("复核状态已变化");
        }
    }

    public int transition(
        long tenant,
        long task,
        int attempt,
        int version,
        String status,
        String error
    ) {
        return db.update(
            """
            UPDATE async_task SET status=?,state_version=state_version+1,error_message=?,
              started_at=COALESCE(started_at,NOW(6)),
              finished_at=IF(? IN ('SUCCEEDED','FAILED','CANCELLED'),NOW(6),NULL)
            WHERE tenant_id=? AND id=? AND attempt=? AND state_version=? AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
            """,
            status,
            error,
            status,
            tenant,
            task,
            attempt,
            version
        );
    }
}
