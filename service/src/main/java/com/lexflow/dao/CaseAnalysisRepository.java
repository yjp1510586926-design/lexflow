package com.lexflow.dao;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CaseAnalysisRepository {

    public record Analysis(
        String background,
        String requirements,
        String modelIdentity,
        String sourcesJson,
        String reportJson,
        String waitingReason,
        String decisionId,
        Long decidedBy,
        String decisionNote,
        int executionProtocol
    ) {}

    public record Parsed(
        long taskId,
        long versionId,
        long fileId,
        String name,
        int fileVersion,
        String sha256,
        String text,
        String segmentsJson
    ) {}

    private final JdbcTemplate db;

    public CaseAnalysisRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Analysis get(long tenant, long task) {
        return db.query(
            """
            SELECT background_text,requirements_text,model_identity,CAST(sources_json AS CHAR),CAST(report_json AS CHAR),
                   waiting_reason,decision_id,decided_by,decision_note,execution_protocol
            FROM case_analysis WHERE tenant_id=? AND task_id=?
            """,
            resultSet ->
                resultSet.next()
                    ? new Analysis(
                          resultSet.getString(1),
                          resultSet.getString(2),
                          resultSet.getString(3),
                          resultSet.getString(4),
                          resultSet.getString(5),
                          resultSet.getString(6),
                          resultSet.getString(7),
                          (Long) resultSet.getObject(8),
                          resultSet.getString(9),
                          resultSet.getInt(10)
                      )
                    : null,
            tenant,
            task
        );
    }

    public Parsed parsed(long tenant, long caseId, long taskId) {
        return parsed(tenant, caseId, taskId, false);
    }

    /** 恢复探测以 lock=true 读取，保持材料共享锁直至资格检查与任务状态 CAS 所在事务提交。 */
    public Parsed parsed(long tenant, long caseId, long taskId, boolean lock) {
        return db.query(
            """
            SELECT t.id,v.id,f.id,f.display_name,v.version_no,v.sha256,p.content_text,CAST(p.segments_json AS CHAR)
            FROM async_task t JOIN task_parse_result p ON p.tenant_id=t.tenant_id AND p.task_id=t.id
            JOIN file_version v ON v.tenant_id=t.tenant_id AND v.id=t.resource_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
            WHERE t.tenant_id=? AND t.case_id=? AND t.id=? AND t.type='DOCUMENT_PARSE' AND t.status='SUCCEEDED'
              AND f.scope='CASE' AND f.case_id=t.case_id AND f.knowledge_base_id IS NULL AND f.status='AVAILABLE'
              AND p.source_sha256=v.sha256
            """ + (lock ? " FOR SHARE" : ""),
            resultSet ->
                resultSet.next()
                    ? new Parsed(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getLong(3),
                          resultSet.getString(4),
                          resultSet.getInt(5),
                          resultSet.getString(6),
                          resultSet.getString(7),
                          resultSet.getString(8)
                      )
                    : null,
            tenant,
            caseId,
            taskId
        );
    }

    /** 按案件材料范围筛选解析来源；调用方须先校验案件读取权限，不以私有任务结果页权限替代材料授权。 */
    public List<Parsed> candidates(long tenant, long caseId) {
        return db.query(
            """
            SELECT t.id,v.id,f.id,f.display_name,v.version_no,v.sha256,p.content_text,CAST(p.segments_json AS CHAR)
            FROM async_task t JOIN task_parse_result p ON p.tenant_id=t.tenant_id AND p.task_id=t.id
            JOIN file_version v ON v.tenant_id=t.tenant_id AND v.id=t.resource_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
            WHERE t.tenant_id=? AND t.case_id=? AND t.type='DOCUMENT_PARSE' AND t.status='SUCCEEDED'
              AND f.scope='CASE' AND f.case_id=t.case_id AND f.knowledge_base_id IS NULL AND f.status='AVAILABLE'
              AND p.source_sha256=v.sha256 AND p.segments_json IS NOT NULL
              AND CHAR_LENGTH(p.content_text) BETWEEN 1 AND 40000
            ORDER BY t.id DESC LIMIT 100
            """,
            (resultSet, rowNumber) ->
                new Parsed(
                    resultSet.getLong(1),
                    resultSet.getLong(2),
                    resultSet.getLong(3),
                    resultSet.getString(4),
                    resultSet.getInt(5),
                    resultSet.getString(6),
                    resultSet.getString(7),
                    resultSet.getString(8)
                ),
            tenant,
            caseId
        );
    }

    public void create(
        long tenant,
        long task,
        String background,
        String requirements,
        String model,
        String sources
    ) {
        db.update(
            "INSERT INTO case_analysis(tenant_id,task_id,background_text,requirements_text,model_identity,sources_json,execution_protocol) VALUES(?,?,?,?,?,?,2)",
            tenant,
            task,
            background,
            requirements,
            model,
            sources
        );
    }

    public boolean currentDeadDelivery(long tenant, long task, int attempt, int version) {
        return (
            db.queryForObject(
                """
                SELECT COUNT(*) FROM task_delivery_recovery
                WHERE tenant_id=? AND task_id=? AND attempt=? AND registered_state_version=? AND delivery_status='DEAD'
                """,
                Integer.class,
                tenant,
                task,
                attempt,
                version
            ) == 1
        );
    }

    /** 投递协调事务统一更新任务、恢复台账与 Outbox；此处仅更新案件分析的投递等待原因。 */
    public void deliveryWaiting(long tenant, long task, String reason) {
        if (
            db.update(
                """
                UPDATE case_analysis a JOIN async_task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id
                SET a.waiting_reason=? WHERE a.tenant_id=? AND a.task_id=? AND t.type='CASE_ANALYSIS' AND t.status='WAITING_HUMAN'
                """,
                reason,
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("案件分析投递状态已变化");
        }
    }

    public void clearDeliveryWaiting(long tenant, long task) {
        if (
            db.update(
                """
                UPDATE case_analysis a JOIN async_task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id
                SET a.waiting_reason=NULL
                WHERE a.tenant_id=? AND a.task_id=? AND a.execution_protocol=2 AND a.waiting_reason='DELIVERY'
                  AND t.type='CASE_ANALYSIS' AND t.status='QUEUED'
                """,
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("案件分析投递状态已变化");
        }
    }

    public void result(long tenant, long task, String report, String reason) {
        if (
            db.update(
                "UPDATE case_analysis SET report_json=?,waiting_reason=? WHERE tenant_id=? AND task_id=?",
                report,
                reason,
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("案件分析输入缺失");
        }
    }

    public void decision(long tenant, long task, String id, long actor, String note) {
        if (
            db.update(
                """
                UPDATE case_analysis SET decision_id=?,decided_by=?,decision_note=?,decided_at=NOW(6)
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
              started_at=COALESCE(started_at,NOW(6)),finished_at=IF(? IN ('SUCCEEDED','FAILED','CANCELLED'),NOW(6),NULL)
            WHERE tenant_id=? AND id=? AND type='CASE_ANALYSIS' AND attempt=? AND state_version=?
              AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
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
