package com.lexflow.dao;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 导出数据库状态仓库；跨表状态变更依赖调用方事务，对象存储 I/O 由事务外的业务协调层处理。 */
@Repository
public class DocumentExportRepository {

    public record Source(
        long version,
        int versionNo,
        String title,
        String status,
        String text,
        String source,
        String reviewStatus
    ) {}

    public record Job(
        long document,
        long version,
        String title,
        int versionNo,
        String status,
        String sha256,
        long size,
        String token,
        boolean leaseExpired,
        String objectKey,
        String expiresAt,
        boolean expired
    ) {}

    public record Ref(long tenant, long task) {}

    public record Artifact(String token, long tenant, long task, String objectKey) {}

    private final JdbcTemplate db;

    public DocumentExportRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Source source(long tenant, long caseId, long document, Long version) {
        return db.query(
            """
            SELECT v.id,v.version_no,d.title,d.status,v.content_text,v.source,v.review_status
            FROM document d JOIN document_version v ON v.tenant_id=d.tenant_id AND v.document_id=d.id
            WHERE d.tenant_id=? AND d.case_id=? AND d.id=? AND v.id=COALESCE(?,d.current_version_id) FOR SHARE
            """,
            resultSet ->
                resultSet.next()
                    ? new Source(
                          resultSet.getLong(1),
                          resultSet.getInt(2),
                          resultSet.getString(3),
                          resultSet.getString(4),
                          resultSet.getString(5),
                          resultSet.getString(6),
                          resultSet.getString(7)
                      )
                    : null,
            tenant,
            caseId,
            document,
            version
        );
    }

    public Job get(long tenant, long task) {
        return db.query(
            """
            SELECT document_id,document_version_id,title,version_no,document_status,source_sha256,size_bytes,
                   claim_token,lease_until<=NOW(6),result_object_key,expires_at,
                   (expired_at IS NOT NULL OR expires_at<=NOW(6))
            FROM document_export_job WHERE tenant_id=? AND task_id=? FOR SHARE
            """,
            resultSet ->
                resultSet.next()
                    ? new Job(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getString(3),
                          resultSet.getInt(4),
                          resultSet.getString(5),
                          resultSet.getString(6),
                          resultSet.getLong(7),
                          resultSet.getString(8),
                          resultSet.getBoolean(9),
                          resultSet.getString(10),
                          resultSet.getObject(11, LocalDateTime.class) == null
                              ? null
                              : resultSet
                                    .getObject(11, LocalDateTime.class)
                                    .toInstant(ZoneOffset.UTC)
                                    .toString(),
                          resultSet.getBoolean(12)
                      )
                    : null,
            tenant,
            task
        );
    }

    public void create(
        long tenant,
        long task,
        long document,
        Source source,
        String hash,
        long size,
        int retentionDays
    ) {
        db.update(
            """
            INSERT INTO document_export_job(tenant_id,task_id,document_id,document_version_id,title,version_no,
              document_status,source_sha256,size_bytes,retention_days) VALUES(?,?,?,?,?,?,?,?,?,?)
            """,
            tenant,
            task,
            document,
            source.version(),
            source.title(),
            source.versionNo(),
            source.status(),
            hash,
            size,
            retentionDays
        );
    }

    /** 执行租约与准确对象键须随任务 RUNNING 状态同事务登记，便于回收未发布或迟到写入的产物。 */
    public void claim(long tenant, long task, String token, String key) {
        db.update(
            "UPDATE document_export_job SET claim_token=?,lease_until=DATE_ADD(NOW(6),INTERVAL 5 MINUTE) WHERE tenant_id=? AND task_id=?",
            token,
            tenant,
            task
        );
        db.update(
            """
            INSERT INTO document_export_artifact(token,tenant_id,task_id,object_key,next_cleanup_at)
            VALUES(?,?,?,?,DATE_ADD(NOW(6),INTERVAL 30 SECOND))
            """,
            token,
            tenant,
            task,
            key
        );
    }

    public void publish(long tenant, long task, String key) {
        db.update(
            """
            UPDATE document_export_job SET result_object_key=?,lease_until=NULL,
              expires_at=IF(retention_days=0,NULL,TIMESTAMPADD(DAY,retention_days,NOW(6)))
            WHERE tenant_id=? AND task_id=?
            """,
            key,
            tenant,
            task
        );
    }

    public void release(long tenant, long task) {
        db.update(
            "UPDATE document_export_job SET claim_token=NULL,lease_until=NULL WHERE tenant_id=? AND task_id=?",
            tenant,
            task
        );
    }

    /** 同时匹配执行批次和状态版本；更新数为 0 时调用方不得发布该执行的结果。 */
    public int transition(
        long tenant,
        long task,
        int attempt,
        int version,
        String state,
        String error,
        String key
    ) {
        return db.update(
            """
            UPDATE async_task SET status=?,state_version=state_version+1,error_message=?,result_object_key=?,
              started_at=IF(?='RUNNING',COALESCE(started_at,NOW(6)),started_at),
              finished_at=IF(? IN ('FAILED','SUCCEEDED','CANCELLED'),NOW(6),NULL)
            WHERE tenant_id=? AND id=? AND type='EXPORT' AND attempt=? AND state_version=?
              AND status IN ('QUEUED','RUNNING')
            """,
            state,
            error,
            key,
            state,
            state,
            tenant,
            task,
            attempt,
            version
        );
    }

    public int requeue(long tenant, long task, int attempt, int version) {
        return db.update(
            """
            UPDATE async_task SET status='QUEUED',attempt=attempt+1,state_version=state_version+1,
              error_message=NULL,result_object_key=NULL,finished_at=NULL
            WHERE tenant_id=? AND id=? AND type='EXPORT' AND status='RUNNING' AND attempt=? AND state_version=?
            """,
            tenant,
            task,
            attempt,
            version
        );
    }

    public String objectKey(String token, long tenant, long task) {
        return db.query(
            "SELECT object_key FROM document_export_artifact WHERE token=? AND tenant_id=? AND task_id=?",
            resultSet -> resultSet.next() ? resultSet.getString(1) : null,
            token,
            tenant,
            task
        );
    }

    public List<Ref> expired() {
        return db.query(
            """
            SELECT j.tenant_id,j.task_id FROM document_export_job j JOIN async_task t
              ON t.tenant_id=j.tenant_id AND t.id=j.task_id
            WHERE t.status='RUNNING' AND j.lease_until<=NOW(6) ORDER BY j.lease_until LIMIT 10
            """,
            (resultSet, rowNumber) -> new Ref(resultSet.getLong(1), resultSet.getLong(2))
        );
    }

    public List<Ref> resultsDue() {
        return db.query(
            """
            SELECT j.tenant_id,j.task_id FROM document_export_job j JOIN async_task t
              ON t.tenant_id=j.tenant_id AND t.id=j.task_id
            WHERE t.status='SUCCEEDED' AND j.expired_at IS NULL AND j.expires_at<=NOW(6)
              AND j.result_object_key IS NOT NULL ORDER BY j.expires_at,j.task_id LIMIT 10
            """,
            (resultSet, rowNumber) -> new Ref(resultSet.getLong(1), resultSet.getLong(2))
        );
    }

    /** 在同一事务中撤销两处下载指针并安排产物清理，防止到期结果继续被任务视图引用。 */
    public boolean expireResult(long tenant, long task) {
        int changed = db.update(
            """
            UPDATE document_export_job SET expired_at=NOW(6),result_object_key=NULL
            WHERE tenant_id=? AND task_id=? AND expired_at IS NULL AND expires_at<=NOW(6)
              AND result_object_key IS NOT NULL
            """,
            tenant,
            task
        );
        if (changed != 1) {
            return false;
        }
        if (
            db.update(
                "UPDATE async_task SET result_object_key=NULL,state_version=state_version+1 WHERE tenant_id=? AND id=? AND type='EXPORT' AND status='SUCCEEDED'",
                tenant,
                task
            ) != 1
        ) {
            throw new IllegalStateException("导出到期状态已变化");
        }
        db.update(
            "UPDATE document_export_artifact SET next_cleanup_at=NOW(6) WHERE tenant_id=? AND task_id=?",
            tenant,
            task
        );
        return true;
    }

    public List<Artifact> cleanupCandidates() {
        return db.query(
            """
            SELECT a.token,a.tenant_id,a.task_id,a.object_key FROM document_export_artifact a
            JOIN document_export_job j ON j.tenant_id=a.tenant_id AND j.task_id=a.task_id
            JOIN async_task t ON t.tenant_id=j.tenant_id AND t.id=j.task_id
            WHERE a.next_cleanup_at<=NOW(6) AND a.object_key<>COALESCE(j.result_object_key,'')
              AND (t.status IN ('FAILED','CANCELLED','SUCCEEDED') OR COALESCE(j.claim_token,'')<>a.token OR j.lease_until<=NOW(6))
            ORDER BY a.next_cleanup_at LIMIT 10
            """,
            (resultSet, rowNumber) ->
                new Artifact(
                    resultSet.getString(1),
                    resultSet.getLong(2),
                    resultSet.getLong(3),
                    resultSet.getString(4)
                )
        );
    }

    public boolean reserveCleanup(Artifact a) {
        return (
            db.update(
                """
                UPDATE document_export_artifact SET next_cleanup_at=DATE_ADD(NOW(6),INTERVAL 1 MINUTE)
                WHERE token=? AND tenant_id=? AND task_id=? AND object_key=? AND next_cleanup_at<=NOW(6)
                """,
                a.token(),
                a.tenant(),
                a.task(),
                a.objectKey()
            ) == 1
        );
    }

    public void cleaned(Artifact a, String error) {
        db.update(
            """
            UPDATE document_export_artifact SET cleanup_attempts=cleanup_attempts+1,cleanup_error=?,
              first_cleaned_at=IF(? IS NULL,COALESCE(first_cleaned_at,NOW(6)),first_cleaned_at),
              last_cleaned_at=IF(? IS NULL,NOW(6),last_cleaned_at),
              next_cleanup_at=DATE_ADD(NOW(6),INTERVAL ? SECOND) WHERE token=? AND tenant_id=? AND task_id=? AND object_key=?
            """,
            error,
            error,
            error,
            error == null ? 86400 : 60,
            a.token(),
            a.tenant(),
            a.task(),
            a.objectKey()
        );
    }
}
