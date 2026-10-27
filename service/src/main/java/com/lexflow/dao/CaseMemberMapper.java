package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.CaseMemberModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CaseMemberMapper extends BaseMapper<CaseMemberModel> {
    @Select(
        """
            SELECT CAST(u.id AS CHAR) AS id, u.display_name AS name, m.member_role AS role
            FROM case_member m
            JOIN sys_user u ON u.id = m.user_id
            WHERE m.tenant_id = #{tenantId} AND m.case_id = #{caseId}
            ORDER BY u.id
        """
    )
    List<Map<String, Object>> listMembersWithUser(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId
    );

    @Select(
        "SELECT member_role FROM case_member WHERE tenant_id = #{tenantId} AND case_id = #{caseId} AND user_id = #{userId}"
    )
    String findMemberRole(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId,
        @Param("userId") long userId
    );

    @Insert(
        "INSERT INTO case_member(tenant_id, case_id, user_id, member_role) VALUES(#{tenantId}, #{caseId}, #{userId}, #{role})"
    )
    int insertMember(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId,
        @Param("userId") long userId,
        @Param("role") String role
    );

    @Delete(
        "DELETE FROM case_member WHERE tenant_id = #{tenantId} AND case_id = #{caseId} AND user_id = #{userId}"
    )
    int deleteMember(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId,
        @Param("userId") long userId
    );

    @Update(
        "UPDATE case_member SET member_role = #{role} WHERE tenant_id = #{tenantId} AND case_id = #{caseId} AND user_id = #{userId}"
    )
    int updateMemberRole(
        @Param("tenantId") long tenantId,
        @Param("caseId") long caseId,
        @Param("userId") long userId,
        @Param("role") String role
    );
}
