package com.lexflow.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 压缩已完成的清理记录，永久保留准确对象键及不可变归属，供后续复查迟到写入。 */
@Repository
public class CleanupTombstoneRepository {

    public record Entry(long id, long tenant, long owner, String key, String token) {}

    private final JdbcTemplate db;

    public CleanupTombstoneRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Entry completedFile(int days) {
        return db.query(
            """
            SELECT j.id,j.tenant_id,j.file_id,j.object_key FROM file_cleanup_job j JOIN file_object f
              ON f.tenant_id=j.tenant_id AND f.id=j.file_id
            WHERE j.status='DONE' AND j.completed_at<=TIMESTAMPADD(DAY,-?,NOW(6))
              AND f.status IN ('DELETED','FAILED')
            ORDER BY j.completed_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            resultSet ->
                resultSet.next()
                    ? new Entry(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getLong(3),
                          resultSet.getString(4),
                          null
                      )
                    : null,
            days
        );
    }

    public Entry completedExport(int days) {
        // 只压缩不再被任务或导出作业引用的产物；期限从首次清理成功开始计算。
        return db.query(
            """
            SELECT a.tenant_id,a.task_id,a.object_key,a.token FROM document_export_artifact a
            JOIN document_export_job j ON j.tenant_id=a.tenant_id AND j.task_id=a.task_id
            JOIN async_task t ON t.tenant_id=j.tenant_id AND t.id=j.task_id
            WHERE a.cleanup_error IS NULL AND a.last_cleaned_at IS NOT NULL
              AND a.first_cleaned_at<=TIMESTAMPADD(DAY,-?,NOW(6))
              AND a.next_cleanup_at<=NOW(6) AND t.status IN ('FAILED','CANCELLED','SUCCEEDED')
              AND a.object_key<>COALESCE(j.result_object_key,'')
              AND a.object_key<>COALESCE(t.result_object_key,'')
            ORDER BY a.last_cleaned_at,a.token LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            resultSet ->
                resultSet.next()
                    ? new Entry(
                          0,
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getString(3),
                          resultSet.getString(4)
                      )
                    : null,
            days
        );
    }

    /** 调用方须保持候选行锁与同一事务；永久墓碑落库且归属核对成功后，才能删掉已完成作业。 */
    public void compactFile(Entry e) {
        db.update(
            """
            INSERT INTO file_cleanup_tombstone(tenant_id,file_id,object_key,next_review_at)
            VALUES(?,?,?,NOW(6)) ON DUPLICATE KEY UPDATE next_review_at=LEAST(next_review_at,NOW(6))
            """,
            e.tenant(),
            e.owner(),
            e.key()
        );
        assertOwner("file_cleanup_tombstone", "file_id", e);
        if (
            db.update(
                "DELETE FROM file_cleanup_job WHERE id=? AND tenant_id=? AND file_id=? AND object_key=? AND status='DONE'",
                e.id(),
                e.tenant(),
                e.owner(),
                e.key()
            ) != 1
        ) {
            throw new IllegalStateException("文件清理记录已变化");
        }
    }

    /** 墓碑保留原 claim token 与准确对象键，不因作业压缩丢失后续迟到写入的清理依据。 */
    public void compactExport(Entry e) {
        db.update(
            """
            INSERT INTO export_cleanup_tombstone(tenant_id,task_id,object_key,claim_token,next_review_at)
            VALUES(?,?,?,?,NOW(6)) ON DUPLICATE KEY UPDATE next_review_at=LEAST(next_review_at,NOW(6))
            """,
            e.tenant(),
            e.owner(),
            e.key(),
            e.token()
        );
        assertOwner("export_cleanup_tombstone", "task_id", e);
        String token = db.queryForObject(
            "SELECT claim_token FROM export_cleanup_tombstone WHERE tenant_id=? AND object_key=?",
            String.class,
            e.tenant(),
            e.key()
        );
        if (!e.token().equals(token)) {
            throw new IllegalStateException("导出清理标识不匹配");
        }
        if (
            db.update(
                "DELETE FROM document_export_artifact WHERE tenant_id=? AND task_id=? AND token=? AND object_key=?",
                e.tenant(),
                e.owner(),
                e.token(),
                e.key()
            ) != 1
        ) {
            throw new IllegalStateException("导出清理记录已变化");
        }
    }

    private void assertOwner(String table, String owner, Entry e) {
        Long id = db.queryForObject(
            "SELECT " + owner + " FROM " + table + " WHERE tenant_id=? AND object_key=?",
            Long.class,
            e.tenant(),
            e.key()
        );
        if (id == null || id != e.owner()) {
            throw new IllegalStateException("清理对象归属不匹配");
        }
    }

    public Entry dueFile() {
        return db.query(
            """
            SELECT x.id,x.tenant_id,x.file_id,x.object_key FROM file_cleanup_tombstone x
            JOIN file_object f ON f.tenant_id=x.tenant_id AND f.id=x.file_id
            WHERE x.next_review_at<=NOW(6) AND f.status IN ('DELETED','FAILED')
              AND EXISTS(SELECT 1 FROM file_version v WHERE v.tenant_id=x.tenant_id AND v.file_id=x.file_id AND v.object_key=x.object_key)
            ORDER BY x.next_review_at,x.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            resultSet ->
                resultSet.next()
                    ? new Entry(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getLong(3),
                          resultSet.getString(4),
                          null
                      )
                    : null
        );
    }

    public Entry dueExport() {
        return db.query(
            """
            SELECT x.id,x.tenant_id,x.task_id,x.object_key,x.claim_token FROM export_cleanup_tombstone x
            JOIN document_export_job j ON j.tenant_id=x.tenant_id AND j.task_id=x.task_id
            JOIN async_task t ON t.tenant_id=j.tenant_id AND t.id=j.task_id
            WHERE x.next_review_at<=NOW(6) AND t.status IN ('FAILED','CANCELLED','SUCCEEDED')
              AND x.object_key<>COALESCE(j.result_object_key,'')
              AND x.object_key<>COALESCE(t.result_object_key,'')
            ORDER BY x.next_review_at,x.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            resultSet ->
                resultSet.next()
                    ? new Entry(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getLong(3),
                          resultSet.getString(4),
                          resultSet.getString(5)
                      )
                    : null
        );
    }

    public void reviewed(Entry e, boolean file, String error) {
        String table = file ? "file_cleanup_tombstone" : "export_cleanup_tombstone";
        db.update(
            "UPDATE " +
                table +
                " SET next_review_at=TIMESTAMPADD(SECOND,?,NOW(6)),last_error=?,last_review_at=IF(? IS NULL,NOW(6),last_review_at) WHERE id=? AND tenant_id=? AND object_key=?",
            error == null ? 86400 : 60,
            error,
            error,
            e.id(),
            e.tenant(),
            e.key()
        );
    }
}
