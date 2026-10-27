package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.CaseModel;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CaseMapper extends BaseMapper<CaseModel> {
    @Select(
        """
            <script>
            SELECT DISTINCT CAST(u.id AS CHAR) AS id, u.display_name AS name
            FROM case_info c
            JOIN tenant_user tu ON tu.tenant_id = c.tenant_id AND tu.user_id = c.owner_id
            JOIN sys_user u ON u.id = tu.user_id
            WHERE c.tenant_id = #{tenantId}
            <if test="!isAdmin">
              AND EXISTS (SELECT 1 FROM case_member m
                          WHERE m.tenant_id = c.tenant_id AND m.case_id = c.id AND m.user_id = #{userId})
            </if>
            ORDER BY name, id
            </script>
        """
    )
    List<Map<String, Object>> listVisibleOwners(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("isAdmin") boolean isAdmin
    );

    @Select("SELECT * FROM case_info WHERE tenant_id = #{tenantId} AND id = #{id} FOR UPDATE")
    CaseModel selectByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select(
        """
            <script>
            SELECT c.*
            FROM case_info c
            <if test="!isAdmin">
            JOIN case_member m ON m.tenant_id = c.tenant_id AND m.case_id = c.id AND m.user_id = #{userId}
            </if>
            WHERE c.tenant_id = #{tenantId}
            <if test="keywordTerm != null and keywordTerm != ''">
              AND (c.title LIKE #{keywordTerm} ESCAPE '!' OR c.case_no LIKE #{keywordTerm} ESCAPE '!')
            </if>
            <if test="status != null and status != ''">
              AND c.status = #{status}
            </if>
            <if test="caseType != null and caseType != ''">
              AND c.case_type = #{caseType}
            </if>
            <if test="ownerId != null">
              AND c.owner_id = #{ownerId}
            </if>
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
        """
    )
    List<CaseModel> listCases(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("isAdmin") boolean isAdmin,
        @Param("keywordTerm") String keywordTerm,
        @Param("status") String status,
        @Param("caseType") String caseType,
        @Param("ownerId") Long ownerId,
        @Param("size") int size,
        @Param("offset") long offset
    );

    @Select(
        """
            <script>
            SELECT COUNT(*)
            FROM case_info c
            <if test="!isAdmin">
            JOIN case_member m ON m.tenant_id = c.tenant_id AND m.case_id = c.id AND m.user_id = #{userId}
            </if>
            WHERE c.tenant_id = #{tenantId}
            <if test="keywordTerm != null and keywordTerm != ''">
              AND (c.title LIKE #{keywordTerm} ESCAPE '!' OR c.case_no LIKE #{keywordTerm} ESCAPE '!')
            </if>
            <if test="status != null and status != ''">
              AND c.status = #{status}
            </if>
            <if test="caseType != null and caseType != ''">
              AND c.case_type = #{caseType}
            </if>
            <if test="ownerId != null">
              AND c.owner_id = #{ownerId}
            </if>
            </script>
        """
    )
    long countCases(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("isAdmin") boolean isAdmin,
        @Param("keywordTerm") String keywordTerm,
        @Param("status") String status,
        @Param("caseType") String caseType,
        @Param("ownerId") Long ownerId
    );

    @Update(
        """
            UPDATE case_info
            SET title = #{title},
                description = #{description},
                status = #{status},
                filed_on = CASE WHEN #{changeFiledOn} THEN #{filedOn,jdbcType=DATE} ELSE filed_on END,
                archived_at = CASE WHEN #{status} = 'ARCHIVED' THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId} AND id = #{id} AND version = #{version}
        """
    )
    int updateWithOptimisticLock(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("version") long version,
        @Param("title") String title,
        @Param("description") String description,
        @Param("status") String status,
        @Param("changeFiledOn") boolean changeFiledOn,
        @Param("filedOn") LocalDate filedOn
    );

    @Update(
        """
            UPDATE case_info
            SET owner_id = #{nextOwner},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId} AND id = #{id}
        """
    )
    int transferOwner(
        @Param("tenantId") long tenantId,
        @Param("id") long id,
        @Param("nextOwner") long nextOwner
    );
}
