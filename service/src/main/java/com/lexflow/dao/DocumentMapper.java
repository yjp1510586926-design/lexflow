package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.DocumentModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentMapper extends BaseMapper<DocumentModel> {
    /** 通过锁定当前读获取最新文书，避免先前权限查询建立的旧事务快照影响后续写入判断。 */
    @Select(
        "SELECT * FROM document WHERE tenant_id=#{tenantId} AND case_id=#{caseId} AND id=#{docId} FOR UPDATE"
    )
    DocumentModel lockCurrent(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId,
        @Param("docId") long docId
    );

    @Select(
        """
        SELECT d.id, d.case_id, d.title, d.status, d.current_version_id,
               COALESCE(dv.version_no,0) AS current_version_no,
               d.created_by, d.created_at, d.version
        FROM document d
        LEFT JOIN document_version dv ON dv.tenant_id=d.tenant_id AND dv.id=d.current_version_id
        WHERE d.tenant_id=#{tenantId} AND d.case_id=#{caseId} AND d.status != 'ARCHIVED'
        ORDER BY d.updated_at DESC, d.id DESC
        """
    )
    List<Map<String, Object>> listWithVersion(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId
    );

    @Select(
        """
        SELECT d.id, d.case_id, d.title, d.status, d.current_version_id,
               COALESCE(dv.version_no,0) AS current_version_no,
               d.created_by, d.created_at, d.version
        FROM document d
        LEFT JOIN document_version dv ON dv.tenant_id=d.tenant_id AND dv.id=d.current_version_id
        WHERE d.tenant_id=#{tenantId} AND d.id=#{docId}
        """
    )
    Map<String, Object> getWithVersion(
        @Param("tenantId") long tenantId,
        @Param("docId") long docId
    );

    @Override
    int updateById(@Param("et") DocumentModel entity);
}
