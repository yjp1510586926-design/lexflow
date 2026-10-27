package com.lexflow.service.audit;

import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立事务写入请求审计；协调器在事务代理之外捕获异常，覆盖事务开启和提交失败。 */
@Service
public class RequestAuditDatabaseWriter {

    private final JdbcTemplate db;

    public RequestAuditDatabaseWriter(JdbcTemplate db) {
        this.db = db;
    }

    public enum Outcome {
        INSERTED,
        DUPLICATE,
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Outcome write(RequestAuditEvent event) {
        try {
            db.update(
                """
                INSERT INTO audit_log(tenant_id,actor_id,action,object_type,object_id,result,trace_id,detail_json,created_at,event_uuid,payload_sha256)
                VALUES(?,?,?,'http_request',?,?,?,?,?,?,?)
                """,
                event.tenantId(),
                event.actorId(),
                event.action(),
                event.objectId(),
                event.result(),
                event.traceId(),
                event.detailJson(),
                Timestamp.from(event.occurredAt()),
                event.eventId(),
                event.payloadHash()
            );
            return Outcome.INSERTED;
        } catch (DuplicateKeyException duplicate) {
            // 事件 UUID 相同还须核对内容摘要，不能把不同内容的身份冲突当作成功重放。
            var hashes = db.queryForList(
                "SELECT payload_sha256 FROM audit_log WHERE event_uuid=?",
                String.class,
                event.eventId()
            );
            if (hashes.size() != 1 || !event.payloadHash().equals(hashes.getFirst())) {
                throw new IllegalStateException("Audit event identity conflict");
            }
            return Outcome.DUPLICATE;
        }
    }
}
