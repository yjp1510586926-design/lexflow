package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.FileObjectModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileObjectMapper extends BaseMapper<FileObjectModel> {
    @Select(
        """
            SELECT fo.id, fo.case_id, fo.display_name, fo.status, fo.created_by, fo.created_at,
                   fv.id AS version_id, fv.mime_type, fv.size_bytes, fv.sha256, fv.version_no
            FROM file_object fo
            LEFT JOIN file_version fv ON fv.tenant_id = fo.tenant_id AND fv.file_id = fo.id AND fv.version_no = 1
            WHERE fo.tenant_id = #{tenantId} AND fo.case_id = #{caseId} AND fo.scope='CASE' AND fo.status != 'DELETED'
            ORDER BY fo.created_at DESC, fo.id DESC
        """
    )
    List<Map<String, Object>> listFilesWithVersion(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId
    );

    @Select(
        """
            SELECT fo.id, fo.case_id, fo.display_name, fo.status, fo.created_by, fo.created_at,
                   fv.id AS version_id, fv.mime_type, fv.size_bytes, fv.sha256, fv.version_no
            FROM file_object fo
            LEFT JOIN file_version fv ON fv.tenant_id = fo.tenant_id AND fv.file_id = fo.id AND fv.version_no = 1
            WHERE fo.tenant_id = #{tenantId} AND fo.id = #{fileId} AND fo.scope='CASE'
        """
    )
    Map<String, Object> getFileViewWithVersion(
        @Param("tenantId") long tenantId,
        @Param("fileId") long fileId
    );

    @Select(
        """
            SELECT fo.tenant_id, fo.case_id, fo.display_name, fo.status,
                   fv.object_key, fv.mime_type, fv.size_bytes
            FROM file_object fo
            JOIN file_version fv ON fv.tenant_id = fo.tenant_id AND fv.file_id = fo.id AND fv.version_no = 1
            WHERE fo.tenant_id = #{tenantId} AND fo.id = #{fileId} AND fo.scope='CASE'
        """
    )
    Map<String, Object> getDownloadInfo(
        @Param("tenantId") long tenantId,
        @Param("fileId") long fileId
    );

    @Select(
        """
            SELECT fo.case_id, fo.status, fv.object_key
            FROM file_object fo
            LEFT JOIN file_version fv ON fv.tenant_id = fo.tenant_id AND fv.file_id = fo.id AND fv.version_no = 1
            WHERE fo.tenant_id = #{tenantId} AND fo.id = #{fileId} AND fo.scope='CASE' AND fo.status != 'DELETED'
        """
    )
    Map<String, Object> getFileForDelete(
        @Param("tenantId") long tenantId,
        @Param("fileId") long fileId
    );

    @Update(
        "UPDATE file_object SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP(6) WHERE tenant_id = #{tenantId} AND id = #{fileId}"
    )
    int markDeleted(@Param("tenantId") long tenantId, @Param("fileId") long fileId);

    @Select(
        """
        SELECT fo.id,fo.knowledge_base_id,fo.display_name,fo.status,fo.created_by,fo.created_at,
               fv.id AS version_id,fv.mime_type,fv.size_bytes,fv.sha256,fv.version_no,
               (SELECT CAST(t.id AS CHAR) FROM async_task t WHERE t.tenant_id=fo.tenant_id
                 AND t.case_id IS NULL AND t.resource_version_id=fv.id AND t.type='DOCUMENT_PARSE'
                 ORDER BY t.id DESC LIMIT 1) AS parse_task_id,
               (SELECT t.status FROM async_task t WHERE t.tenant_id=fo.tenant_id
                 AND t.case_id IS NULL AND t.resource_version_id=fv.id AND t.type='DOCUMENT_PARSE'
                 ORDER BY t.id DESC LIMIT 1) AS parse_status
        FROM file_object fo
        LEFT JOIN file_version fv ON fv.tenant_id=fo.tenant_id AND fv.file_id=fo.id AND fv.version_no=1
        WHERE fo.tenant_id=#{tenantId} AND fo.knowledge_base_id=#{baseId}
          AND fo.scope='TENANT' AND fo.case_id IS NULL AND fo.status!='DELETED'
        ORDER BY fo.created_at DESC,fo.id DESC
        """
    )
    List<Map<String, Object>> listTenantKnowledgeFiles(
        @Param("tenantId") long tenantId,
        @Param("baseId") long baseId
    );

    @Select(
        """
        SELECT fo.id,fo.knowledge_base_id,fo.display_name,fo.status,fo.created_by,fo.created_at,
               fv.id AS version_id,fv.mime_type,fv.size_bytes,fv.sha256,fv.version_no,fv.object_key,
               (SELECT CAST(t.id AS CHAR) FROM async_task t WHERE t.tenant_id=fo.tenant_id
                 AND t.case_id IS NULL AND t.resource_version_id=fv.id AND t.type='DOCUMENT_PARSE'
                 ORDER BY t.id DESC LIMIT 1) AS parse_task_id,
               (SELECT t.status FROM async_task t WHERE t.tenant_id=fo.tenant_id
                 AND t.case_id IS NULL AND t.resource_version_id=fv.id AND t.type='DOCUMENT_PARSE'
                 ORDER BY t.id DESC LIMIT 1) AS parse_status
        FROM file_object fo
        LEFT JOIN file_version fv ON fv.tenant_id=fo.tenant_id AND fv.file_id=fo.id AND fv.version_no=1
        WHERE fo.tenant_id=#{tenantId} AND fo.knowledge_base_id=#{baseId} AND fo.id=#{fileId}
          AND fo.scope='TENANT' AND fo.case_id IS NULL AND fo.status!='DELETED'
        """
    )
    Map<String, Object> tenantKnowledgeFile(
        @Param("tenantId") long tenantId,
        @Param("baseId") long baseId,
        @Param("fileId") long fileId
    );
}
