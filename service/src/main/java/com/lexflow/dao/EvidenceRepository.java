package com.lexflow.dao;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EvidenceRepository {
    String LIVE =
        " FROM evidence e JOIN file_version v ON v.tenant_id=e.tenant_id AND v.id=e.file_version_id" +
        " JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id AND f.case_id=e.case_id" +
        " WHERE e.tenant_id=#{tenant} AND e.case_id=#{caseId} AND f.scope='CASE' AND f.status='AVAILABLE'";
    String FIELDS = "SELECT e.*, f.id AS file_id, f.display_name, v.version_no, v.sha256";

    @Select(FIELDS + LIVE + " ORDER BY e.sort_order,e.id LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> list(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("size") int size,
        @Param("offset") long offset
    );

    @Select("SELECT COUNT(*)" + LIVE)
    long count(@Param("tenant") long tenant, @Param("caseId") long caseId);

    @Select(FIELDS + LIVE + " AND e.file_version_id=#{fileVersionId}")
    Map<String, Object> get(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("fileVersionId") long fileVersionId
    );

    @Select(
        """
        SELECT f.id FROM file_object f JOIN file_version v ON v.tenant_id=f.tenant_id AND v.file_id=f.id
        WHERE f.tenant_id=#{tenant} AND f.case_id=#{caseId} AND f.scope='CASE' AND f.status='AVAILABLE'
          AND v.id=#{fileVersionId} FOR UPDATE
        """
    )
    Long lockSource(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("fileVersionId") long fileVersionId
    );

    @Insert(
        """
        INSERT INTO evidence(tenant_id,case_id,file_version_id,source,collected_at,description,sort_order,created_by,updated_by)
        VALUES(#{tenant},#{caseId},#{fileVersionId},#{source},#{collectedAt,jdbcType=TIMESTAMP},#{description},#{sortOrder},#{actor},#{actor})
        """
    )
    int create(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("fileVersionId") long fileVersionId,
        @Param("source") String source,
        @Param("collectedAt") Timestamp collectedAt,
        @Param("description") String description,
        @Param("sortOrder") int sortOrder,
        @Param("actor") long actor
    );

    @Update(
        """
        UPDATE evidence SET source=#{source},collected_at=#{collectedAt,jdbcType=TIMESTAMP},description=#{description},
          sort_order=#{sortOrder},version=version+1,updated_by=#{actor},updated_at=CURRENT_TIMESTAMP(6)
        WHERE tenant_id=#{tenant} AND case_id=#{caseId} AND file_version_id=#{fileVersionId} AND version=#{version}
        """
    )
    int update(
        @Param("tenant") long tenant,
        @Param("caseId") long caseId,
        @Param("fileVersionId") long fileVersionId,
        @Param("source") String source,
        @Param("collectedAt") Timestamp collectedAt,
        @Param("description") String description,
        @Param("sortOrder") int sortOrder,
        @Param("actor") long actor,
        @Param("version") long version
    );
}
