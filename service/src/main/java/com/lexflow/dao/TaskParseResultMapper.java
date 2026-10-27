package com.lexflow.dao;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskParseResultMapper {
    @Insert(
        """
        INSERT INTO task_parse_result(tenant_id,task_id,source_sha256,content_text,segments_json)
        VALUES(#{tenantId},#{taskId},#{sha256},#{content},#{segmentsJson})
        """
    )
    int insert(
        @Param("tenantId") long tenantId,
        @Param("taskId") long taskId,
        @Param("sha256") String sha256,
        @Param("content") String content,
        @Param("segmentsJson") String segmentsJson
    );

    @Select(
        """
        SELECT source_sha256 AS sourceSha256, content_text AS text, segments_json AS segmentsJson
        FROM task_parse_result WHERE tenant_id=#{tenantId} AND task_id=#{taskId}
        """
    )
    Map<String, Object> find(@Param("tenantId") long tenantId, @Param("taskId") long taskId);
}
