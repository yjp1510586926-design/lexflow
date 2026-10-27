package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.TenantUserModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TenantUserMapper extends BaseMapper<TenantUserModel> {
    @Select(
        "SELECT status FROM tenant_user WHERE tenant_id=#{tenantId} AND user_id=#{userId} FOR UPDATE"
    )
    String lockMembershipStatus(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Select(
        """
            SELECT COUNT(*)
            FROM tenant_user tu
            JOIN sys_user u ON u.id = tu.user_id
            WHERE tu.tenant_id = #{tenantId} AND tu.user_id = #{userId} AND tu.status = 'ACTIVE' AND u.status = 'ACTIVE'
        """
    )
    long countActiveUserInTenant(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Select(
        """
            SELECT CAST(u.id AS CHAR) AS id, u.display_name AS name
            FROM tenant_user tu
            JOIN sys_user u ON u.id = tu.user_id
            WHERE tu.tenant_id = #{tenantId} AND tu.status = 'ACTIVE' AND u.status = 'ACTIVE'
            ORDER BY u.id
            LIMIT 100
        """
    )
    List<Map<String, Object>> listActiveTenantUsers(@Param("tenantId") long tenantId);

    @Update(
        "UPDATE tenant_user SET status = #{status} WHERE tenant_id = #{tenantId} AND user_id = #{userId}"
    )
    int updateStatus(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("status") String status
    );

    @Insert(
        "INSERT INTO tenant_user(tenant_id, user_id, status) VALUES(#{tenantId}, #{userId}, #{status})"
    )
    int insertMembership(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("status") String status
    );

    @Select(
        "SELECT COUNT(*) FROM tenant_user WHERE tenant_id = #{tenantId} AND user_id = #{userId}"
    )
    long countMembership(@Param("tenantId") long tenantId, @Param("userId") long userId);
}
