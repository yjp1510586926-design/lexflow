package com.lexflow.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 持久记录不可变的向量位置与归属，供退役和回收使用；不保存正文、凭据或向量数据。 */
@Repository
public class KnowledgeVectorArtifactRepository {

    public record Artifact(
        long id,
        long tenantId,
        long baseId,
        Long caseId,
        String scope,
        long documentId,
        long fileVersionId,
        int indexVersion,
        Long taskId,
        String collectionName,
        int dimension,
        String modelIdentity,
        boolean retired,
        String leaseToken,
        int attempts
    ) {}

    private final JdbcTemplate db;

    public KnowledgeVectorArtifactRepository(JdbcTemplate db) {
        this.db = db;
    }

    private static final String COLUMNS =
        "id,tenant_id,knowledge_base_id,case_id,scope,knowledge_document_id,file_version_id,index_version,task_id,collection_name,embedding_dimension,model_identity,retired_at,lease_token,cleanup_attempt";

    private static Artifact row(ResultSet r) throws SQLException {
        return new Artifact(
            r.getLong(1),
            r.getLong(2),
            r.getLong(3),
            r.getObject(4, Long.class),
            r.getString(5),
            r.getLong(6),
            r.getLong(7),
            r.getInt(8),
            r.getObject(9, Long.class),
            r.getString(10),
            r.getInt(11),
            r.getString(12),
            r.getTimestamp(13) != null,
            r.getString(14),
            r.getInt(15)
        );
    }

    public Artifact location(long tenant, long document, int version, String collection) {
        return db.query(
            "SELECT " +
                COLUMNS +
                " FROM knowledge_vector_artifact WHERE tenant_id=? AND knowledge_document_id=? AND index_version=? AND collection_name=? FOR UPDATE",
            r -> r.next() ? row(r) : null,
            tenant,
            document,
            version,
            collection
        );
    }

    public void insert(
        long tenant,
        long base,
        Long caseId,
        String scope,
        long document,
        long fileVersion,
        int version,
        long task,
        String collection,
        int dimension,
        String model
    ) {
        db.update(
            "INSERT INTO knowledge_vector_artifact(tenant_id,knowledge_base_id,case_id,scope,knowledge_document_id,file_version_id,index_version,task_id,collection_name,embedding_dimension,model_identity) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            tenant,
            base,
            caseId,
            scope,
            document,
            fileVersion,
            version,
            task,
            collection,
            dimension,
            model
        );
    }

    public List<Long> candidates(int limit) {
        return db.queryForList(
            "SELECT id FROM knowledge_vector_artifact WHERE next_check_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6)) ORDER BY next_check_at,id LIMIT ?",
            Long.class,
            limit
        );
    }

    /** 先读取定位信息，服务层再按任务、文档、产物的顺序加锁，避免回收与索引发布的锁顺序相反。 */
    public Artifact find(long id) {
        return db.query(
            "SELECT " + COLUMNS + " FROM knowledge_vector_artifact WHERE id=?",
            r -> r.next() ? row(r) : null,
            id
        );
    }

    public Artifact lock(long id) {
        return db.query(
            "SELECT " +
                COLUMNS +
                " FROM knowledge_vector_artifact WHERE id=? AND next_check_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6)) FOR UPDATE",
            r -> r.next() ? row(r) : null,
            id
        );
    }

    public void retain(long id) {
        db.update(
            "UPDATE knowledge_vector_artifact SET next_check_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=?",
            id
        );
    }

    /** 调用方持有产物行锁并确认不再使用后，登记退役标记与本次回收租约。 */
    public void reserve(long id, String token) {
        db.update(
            "UPDATE knowledge_vector_artifact SET retired_at=COALESCE(retired_at,CURRENT_TIMESTAMP(6)),lease_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),next_check_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",
            token,
            id
        );
    }

    /** 仅租约持有者可回写；旧请求迟到时不能释放其他实例取得的新租约。 */
    public int finish(long id, String token, boolean success, int delay) {
        return db.update(
            "UPDATE knowledge_vector_artifact SET lease_token=NULL,lease_until=NULL,cleanup_attempt=IF(?,0,LEAST(cleanup_attempt+1,30)),last_cleaned_at=IF(?,CURRENT_TIMESTAMP(6),last_cleaned_at),next_check_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE id=? AND lease_token=? AND retired_at IS NOT NULL",
            success,
            success,
            delay,
            id,
            token
        );
    }
}
