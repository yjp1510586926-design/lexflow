package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 结果回写核对 attempt 与 state_version；取消推进状态版本，拒绝旧执行批次回写。 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTaskModel> {
    @Select(
        """
        SELECT COUNT(*) FROM file_version v
        JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
        WHERE v.tenant_id=#{tenant} AND v.id=#{version} AND f.case_id=#{caseId}
          AND f.scope='CASE' AND f.status='AVAILABLE'
        """
    )
    int countAvailableSource(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("version") long version
    );

    @Select(
        """
        SELECT COUNT(*) FROM file_version v
        JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
        WHERE v.tenant_id=#{tenant} AND v.id=#{version} AND f.case_id=#{caseId}
          AND f.scope='CASE' AND f.status='AVAILABLE' AND v.sha256=#{sha256}
        """
    )
    int sourceMatches(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("version") long version,
        @Param("sha256") String sha256
    );

    @Select(
        """
        SELECT COUNT(*) FROM file_version v
        JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
        JOIN knowledge_base b ON b.tenant_id=f.tenant_id AND b.id=f.knowledge_base_id
        WHERE v.tenant_id=#{tenant} AND v.id=#{version} AND f.knowledge_base_id=#{baseId}
          AND f.scope='TENANT' AND f.case_id IS NULL AND f.status='AVAILABLE'
          AND b.scope='TENANT' AND b.case_id IS NULL AND b.status='ACTIVE'
        """
    )
    int countTenantSource(
        @Param("tenant") long tenant,
        @Param("baseId") long baseId,
        @Param("version") long version
    );

    @Select(
        """
        SELECT b.id FROM async_task t
        JOIN file_version v ON v.tenant_id=t.tenant_id AND v.id=t.resource_version_id
        JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
        JOIN knowledge_base b ON b.tenant_id=f.tenant_id AND b.id=f.knowledge_base_id
        WHERE t.tenant_id=#{tenant} AND t.id=#{taskId} AND t.case_id IS NULL
          AND t.type IN ('DOCUMENT_PARSE','KNOWLEDGE_INGEST')
          AND f.scope='TENANT' AND f.case_id IS NULL AND f.status='AVAILABLE'
          AND b.scope='TENANT' AND b.case_id IS NULL AND b.status='ACTIVE'
        """
    )
    Long tenantSourceBaseForTask(@Param("tenant") long tenant, @Param("taskId") long taskId);

    @Select(
        """
        SELECT COUNT(*) FROM file_version v
        JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
        JOIN knowledge_base b ON b.tenant_id=f.tenant_id AND b.id=f.knowledge_base_id
        WHERE v.tenant_id=#{tenant} AND v.id=#{version} AND f.knowledge_base_id=#{baseId}
          AND f.scope='TENANT' AND f.case_id IS NULL AND f.status='AVAILABLE'
          AND b.scope='TENANT' AND b.case_id IS NULL AND b.status='ACTIVE' AND v.sha256=#{sha256}
        """
    )
    int tenantSourceMatches(
        @Param("tenant") long tenant,
        @Param("baseId") long baseId,
        @Param("version") long version,
        @Param("sha256") String sha256
    );

    @Select("SELECT * FROM async_task WHERE tenant_id = #{tenantId} AND id = #{id} FOR UPDATE")
    AsyncTaskModel selectByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    /** 仅锁定任务，不进行租户授权；按全局任务编号调用前后，服务层仍须验证资源归属。 */
    @Select("SELECT * FROM async_task WHERE id = #{id} FOR UPDATE")
    AsyncTaskModel selectLockById(@Param("id") long id);

    @Select(
        """
            SELECT t.id, t.created_by, t.tenant_id, t.case_id, t.type, t.status, t.resource_version_id,
                   t.idempotency_key, t.attempt, t.state_version,
                   fv.object_key, fv.mime_type, fv.sha256, fv.size_bytes,f.scope,f.knowledge_base_id
            FROM async_task t
            JOIN file_version fv ON fv.id = t.resource_version_id AND fv.tenant_id = t.tenant_id
            JOIN file_object f ON f.id=fv.file_id AND f.tenant_id=t.tenant_id AND f.status='AVAILABLE'
              AND ((f.scope='CASE' AND f.case_id=t.case_id AND f.knowledge_base_id IS NULL)
                OR (f.scope='TENANT' AND f.case_id IS NULL AND t.case_id IS NULL
                    AND t.type IN ('DOCUMENT_PARSE','KNOWLEDGE_INGEST')
                    AND EXISTS (SELECT 1 FROM knowledge_base b WHERE b.tenant_id=f.tenant_id
                      AND b.id=f.knowledge_base_id AND b.scope='TENANT' AND b.case_id IS NULL AND b.status='ACTIVE')))
            WHERE t.id = #{taskId} AND t.type IN ('DOCUMENT_PARSE','KNOWLEDGE_INGEST','CONTRACT_REVIEW','CASE_ANALYSIS')
        """
    )
    Map<String, Object> getTaskContext(@Param("taskId") long taskId);

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,finished_at=NOW(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='DOCUMENT_PARSE' AND case_id IS NULL
          AND state_version=#{stateVersion} AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','WAITING_HUMAN')
        """
    )
    int cancelTenantParse(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,finished_at=NOW(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='DOCUMENT_PARSE'
          AND state_version=#{stateVersion} AND status='WAITING_HUMAN'
        """
    )
    int cancelWaitingParse(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
            UPDATE async_task
            SET status = 'CANCEL_REQUESTED',
                state_version = state_version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId} AND id = #{id} AND state_version = #{stateVersion}
        """
    )
    int requestCancel(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,
            finished_at=NOW(6),updated_at=CURRENT_TIMESTAMP(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='KNOWLEDGE_INGEST'
          AND state_version=#{stateVersion} AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
        """
    )
    int cancelKnowledgeIndex(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,finished_at=NOW(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='CONTRACT_REVIEW'
          AND state_version=#{stateVersion} AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
        """
    )
    int cancelContractReview(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,finished_at=NOW(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='CASE_ANALYSIS'
          AND state_version=#{stateVersion} AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
        """
    )
    int cancelCaseAnalysis(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,finished_at=NOW(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='DOCUMENT_GENERATE'
          AND state_version=#{stateVersion} AND status IN ('QUEUED','RUNNING','WAITING_HUMAN')
        """
    )
    int cancelDocumentGeneration(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE async_task SET status='CANCELLED',state_version=state_version+1,finished_at=NOW(6)
        WHERE tenant_id=#{tenantId} AND id=#{id} AND type='EXPORT'
          AND state_version=#{stateVersion} AND status IN ('QUEUED','RUNNING')
        """
    )
    int cancelExport(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("stateVersion") int stateVersion
    );

    @Update(
        """
        UPDATE knowledge_document d JOIN knowledge_index_job j
          ON j.tenant_id=d.tenant_id AND j.knowledge_document_id=d.id
        SET d.status=IF(d.status='ACTIVE','ACTIVE','FAILED'),d.pending_index_task_id=NULL
        WHERE j.tenant_id=#{tenantId} AND j.task_id=#{id} AND d.pending_index_task_id=j.task_id
          AND d.last_index_version=j.index_version AND d.status IN ('INDEXING','ACTIVE')
        """
    )
    int failKnowledgeDocumentOnCancel(@Param("tenantId") long tenantId, @Param("id") long id);

    /** attempt 隔离重试批次，stateVersion 隔离取消或恢复操作；旧结果更新为 0 时不得继续发布。 */
    @Update(
        """
            UPDATE async_task
            SET status = #{newStatus},
                state_version = state_version + 1,
                result_object_key = #{resultObjectKey},
                error_message = #{errorMessage},
                finished_at = NOW(),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id} AND attempt = #{attempt} AND state_version = #{stateVersion}
              AND status IN ('QUEUED','RUNNING')
        """
    )
    int applyTaskResult(
        @Param("id") long id,
        @Param("attempt") int attempt,
        @Param("stateVersion") int stateVersion,
        @Param("newStatus") String newStatus,
        @Param("resultObjectKey") String resultObjectKey,
        @Param("errorMessage") String errorMessage
    );
}
