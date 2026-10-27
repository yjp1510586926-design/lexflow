package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.SysUserModel;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUserModel> {
    @Select(
        """
            SELECT u.id, u.password_hash, u.session_version, u.display_name
            FROM sys_user u
            JOIN tenant_user tu ON tu.user_id = u.id
            JOIN tenant t ON t.id = tu.tenant_id
            WHERE u.username = #{username}
              AND tu.tenant_id = #{tenantId}
              AND u.status = 'ACTIVE'
              AND tu.status = 'ACTIVE'
              AND t.status = 'ACTIVE'
            LIMIT 1
        """
    )
    Map<String, Object> findAuthUser(
        @Param("username") String username,
        @Param("tenantId") long tenantId
    );

    @Select(
        """
            SELECT COUNT(*)
            FROM sys_user u
            JOIN tenant_user tu ON u.id = tu.user_id
            JOIN tenant t ON t.id = tu.tenant_id
            WHERE u.id = #{userId}
              AND tu.tenant_id = #{tenantId}
              AND u.session_version = #{sessionVersion}
              AND u.status = 'ACTIVE'
              AND tu.status = 'ACTIVE'
              AND t.status = 'ACTIVE'
        """
    )
    long countActiveSession(
        @Param("userId") long userId,
        @Param("tenantId") long tenantId,
        @Param("sessionVersion") long sessionVersion
    );

    @Select(
        """
            SELECT COUNT(*)
            FROM user_role ur
            JOIN role_permission rp ON rp.tenant_id = ur.tenant_id AND rp.role_id = ur.role_id
            WHERE ur.tenant_id = #{tenantId}
              AND ur.user_id = #{userId}
              AND rp.permission_code = #{code}
        """
    )
    long countPermission(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("code") String code
    );

    @Select(
        """
            SELECT COUNT(*)
            FROM user_role ur
            JOIN sys_role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE ur.tenant_id = #{tenantId}
              AND ur.user_id = #{userId}
              AND r.code = 'TENANT_ADMIN'
        """
    )
    long countAdminRole(@Param("tenantId") long tenantId, @Param("userId") long userId);
}
