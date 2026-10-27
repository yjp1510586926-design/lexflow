package com.lexflow.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 仅存测试意图和安全结果；不存模型凭证或供应商响应。 */
@Repository
public class AiConfigurationTestRepository {

    public record Row(
        long id,
        long tenant,
        long creator,
        long sessionVersion,
        String purpose,
        String revision,
        String requestId,
        String status,
        Integer elapsedMs,
        Integer dimension,
        String errorCode,
        Instant createdAt,
        boolean expired
    ) {}

    private final JdbcTemplate db;

    public AiConfigurationTestRepository(JdbcTemplate db) {
        this.db = db;
    }

    private static final String COLUMNS = """
    SELECT id,tenant_id,creator_id,session_version,purpose,config_revision,request_id,status,
           elapsed_ms,dimension,error_code,created_at,
           COALESCE(started_at,created_at)<DATE_SUB(NOW(6),INTERVAL 2 MINUTE) AS expired
    FROM ai_configuration_test
    """;

    private Row row(ResultSet r) throws SQLException {
        return new Row(
            r.getLong(1),
            r.getLong(2),
            r.getLong(3),
            r.getLong(4),
            r.getString(5),
            r.getString(6),
            r.getString(7),
            r.getString(8),
            (Integer) r.getObject(9),
            (Integer) r.getObject(10),
            r.getString(11),
            r.getTimestamp(12).toInstant(),
            r.getBoolean(13)
        );
    }

    public Row get(long tenant, long id, boolean lock) {
        return db.query(
            COLUMNS + " WHERE tenant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            r -> r.next() ? row(r) : null,
            tenant,
            id
        );
    }

    public Row byRequest(long tenant, String request) {
        return db.query(
            COLUMNS + " WHERE tenant_id=? AND request_id=? FOR UPDATE",
            r -> r.next() ? row(r) : null,
            tenant,
            request
        );
    }

    public Row latest(long tenant, String purpose, String revision) {
        return db.query(
            COLUMNS +
                " WHERE tenant_id=? AND purpose=? AND config_revision=? ORDER BY id DESC LIMIT 1 FOR UPDATE",
            r -> r.next() ? row(r) : null,
            tenant,
            purpose,
            revision
        );
    }

    public long create(
        long tenant,
        long creator,
        long session,
        String purpose,
        String revision,
        String request
    ) {
        var key = new GeneratedKeyHolder();
        db.update(c -> {
            var s = c.prepareStatement(
                """
                INSERT INTO ai_configuration_test(tenant_id,creator_id,session_version,purpose,config_revision,request_id)
                VALUES(?,?,?,?,?,?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            s.setLong(1, tenant);
            s.setLong(2, creator);
            s.setLong(3, session);
            s.setString(4, purpose);
            s.setString(5, revision);
            s.setString(6, request);
            return s;
        }, key);
        return key.getKey().longValue();
    }

    public void expire(long tenant, String purpose) {
        db.update(
            """
            UPDATE ai_configuration_test SET status='UNKNOWN',error_code='RESPONSE_UNKNOWN',finished_at=NOW(6)
            WHERE tenant_id=? AND purpose=? AND status IN ('PREPARED','PROCESSING')
              AND COALESCE(started_at,created_at)<DATE_SUB(NOW(6),INTERVAL 2 MINUTE)
            """,
            tenant,
            purpose
        );
    }

    public int active(long tenant, String purpose) {
        return db.queryForObject(
            "SELECT COUNT(*) FROM ai_configuration_test WHERE tenant_id=? AND purpose=? AND status IN ('PREPARED','PROCESSING')",
            Integer.class,
            tenant,
            purpose
        );
    }

    public int recent(long tenant, String purpose) {
        return db.queryForObject(
            "SELECT COUNT(*) FROM ai_configuration_test WHERE tenant_id=? AND purpose=? AND created_at>DATE_SUB(NOW(6),INTERVAL 1 MINUTE)",
            Integer.class,
            tenant,
            purpose
        );
    }

    public int begin(long tenant, long id) {
        return db.update(
            "UPDATE ai_configuration_test SET status='PROCESSING',started_at=NOW(6) WHERE tenant_id=? AND id=? AND status='PREPARED'",
            tenant,
            id
        );
    }

    public int finish(
        long tenant,
        long id,
        String status,
        Integer elapsed,
        Integer dimension,
        String error
    ) {
        return db.update(
            """
            UPDATE ai_configuration_test SET status=?,elapsed_ms=?,dimension=?,error_code=?,finished_at=NOW(6)
            WHERE tenant_id=? AND id=? AND status='PROCESSING'
            """,
            status,
            elapsed,
            dimension,
            error,
            tenant,
            id
        );
    }
}
