package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.AuditLogModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogModel> {
    @Select(
        """
            <script>
            SELECT CAST(al.id AS CHAR) AS id, al.action, al.object_type,
                   CAST(al.object_id AS CHAR) AS object_id, al.result,
                   CAST(al.actor_id AS CHAR) AS actor_id, u.display_name AS actor_name,
                   al.trace_id, al.created_at, al.detail_json
            FROM audit_log al
            LEFT JOIN sys_user u ON u.id = al.actor_id
            WHERE al.tenant_id = #{tenantId}
            <if test="actorId != null">
              AND al.actor_id = #{actorId}
            </if>
            <if test="action != null and action != ''">
              AND al.action = #{action}
            </if>
            ORDER BY al.created_at DESC, al.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
        """
    )
    List<Map<String, Object>> listAuditLogs(
        @Param("tenantId") long tenantId,
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("size") int size,
        @Param("offset") long offset
    );

    @Select(
        """
            <script>
            SELECT COUNT(*)
            FROM audit_log al
            WHERE al.tenant_id = #{tenantId}
            <if test="actorId != null">
              AND al.actor_id = #{actorId}
            </if>
            <if test="action != null and action != ''">
              AND al.action = #{action}
            </if>
            </script>
        """
    )
    long countAuditLogs(
        @Param("tenantId") long tenantId,
        @Param("actorId") Long actorId,
        @Param("action") String action
    );
}
