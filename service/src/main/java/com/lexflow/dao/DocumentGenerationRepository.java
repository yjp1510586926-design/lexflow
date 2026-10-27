package com.lexflow.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentGenerationRepository {

    public record Generation(
        long documentId,
        long sourceVersionId,
        String instructions,
        String caseContext,
        String modelRevision,
        Long resultVersionId,
        String missingInformation,
        int executionProtocol
    ) {}

    public record Source(String title, String content, String status) {}

    private final JdbcTemplate db;

    public DocumentGenerationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Generation get(long tenant, long task) {
        return db.query(
            """
            SELECT document_id,source_version_id,instructions,CAST(case_context_json AS CHAR),model_revision,
                   result_version_id,CAST(missing_information_json AS CHAR),execution_protocol
            FROM document_generation WHERE tenant_id=? AND task_id=?
            """,
            resultSet ->
                resultSet.next()
                    ? new Generation(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getString(3),
                          resultSet.getString(4),
                          resultSet.getString(5),
                          (Long) resultSet.getObject(6),
                          resultSet.getString(7),
                          resultSet.getInt(8)
                      )
                    : null,
            tenant,
            task
        );
    }

    public Source source(long tenant, long caseId, long doc, long version) {
        return db.query(
            """
            SELECT d.title,COALESCE(v.content_text,''),d.status FROM document d
            JOIN document_version v ON v.tenant_id=d.tenant_id AND v.document_id=d.id AND v.id=?
            WHERE d.tenant_id=? AND d.case_id=? AND d.id=? AND d.status<>'ARCHIVED'
            """,
            resultSet ->
                resultSet.next()
                    ? new Source(
                          resultSet.getString(1),
                          resultSet.getString(2),
                          resultSet.getString(3)
                      )
                    : null,
            version,
            tenant,
            caseId,
            doc
        );
    }

    public String revision(long tenant) {
        return db.query(
            "SELECT revision FROM ai_configuration WHERE tenant_id=? AND purpose='LLM'",
            resultSet -> resultSet.next() ? resultSet.getString(1) : null,
            tenant
        );
    }

    public void create(
        long tenant,
        long task,
        long doc,
        long version,
        String instructions,
        String context,
        String revision
    ) {
        db.update(
            """
            INSERT INTO document_generation(tenant_id,task_id,document_id,source_version_id,instructions,
                case_context_json,model_revision,execution_protocol) VALUES(?,?,?,?,?,?,?,2)
            """,
            tenant,
            task,
            doc,
            version,
            instructions,
            context,
            revision
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

    public void result(long tenant, long task, long version, String missing) {
        if (
            db.update(
                """
                UPDATE document_generation SET result_version_id=?,missing_information_json=?
                WHERE tenant_id=? AND task_id=? AND result_version_id IS NULL
                """,
                version,
                missing,
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("文书生成结果已变化");
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
            WHERE tenant_id=? AND id=? AND type='DOCUMENT_GENERATE' AND attempt=? AND state_version=?
                AND status IN ('QUEUED','RUNNING')
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
