package com.lexflow.dao;

import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileCleanupMapper {
    @Insert(
        "INSERT INTO file_cleanup_job(tenant_id,file_id,object_key) SELECT tenant_id,file_id,object_key FROM file_version WHERE tenant_id=#{tenantId} AND file_id=#{fileId} ON DUPLICATE KEY UPDATE object_key=file_cleanup_job.object_key"
    )
    int enqueueVersions(@Param("tenantId") long tenantId, @Param("fileId") long fileId);

    @Select(
        "SELECT j.* FROM file_cleanup_job j JOIN file_object f ON f.tenant_id=j.tenant_id AND f.id=j.file_id WHERE j.status='PENDING' AND j.next_attempt_at<=CURRENT_TIMESTAMP(6) AND f.status IN ('DELETED','FAILED','UPLOADING') ORDER BY j.next_attempt_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    Map<String, Object> lockNext();

    @Update(
        "UPDATE file_cleanup_job SET status='DONE',attempts=attempts+1,last_error=NULL,completed_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND status='PENDING'"
    )
    int done(@Param("id") long id);

    @Update(
        "UPDATE file_cleanup_job SET attempts=attempts+1,last_error=#{error},next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND status='PENDING'"
    )
    int retry(@Param("id") long id, @Param("delay") int delay, @Param("error") String error);

    @Update(
        "UPDATE file_cleanup_job SET next_attempt_at=TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=#{tenantId} AND file_id=#{fileId}"
    )
    int deferUpload(@Param("tenantId") long tenantId, @Param("fileId") long fileId);

    @Delete("DELETE FROM file_cleanup_job WHERE tenant_id=#{tenantId} AND file_id=#{fileId}")
    int cancelUpload(@Param("tenantId") long tenantId, @Param("fileId") long fileId);

    @Update(
        "UPDATE file_cleanup_job SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP(6),completed_at=NULL WHERE tenant_id=#{tenantId} AND file_id=#{fileId}"
    )
    int rearm(@Param("tenantId") long tenantId, @Param("fileId") long fileId);

    @Update(
        "UPDATE file_object SET status='FAILED' WHERE tenant_id=#{tenantId} AND id=#{fileId} AND status='UPLOADING'"
    )
    int expireUpload(@Param("tenantId") long tenantId, @Param("fileId") long fileId);
}
