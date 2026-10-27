package com.lexflow.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lexflow.dao.model.SysRoleModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRoleModel> {
    @Select(
        """
        SELECT r.code FROM user_role ur
        JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
        WHERE ur.tenant_id=#{tenantId} AND ur.user_id=#{userId}
        ORDER BY r.code FOR SHARE
        """
    )
    List<String> currentUserRoles(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Select(
        """
        SELECT rp.permission_code FROM tenant_user tu
        JOIN sys_user u ON u.id=tu.user_id
        JOIN user_role ur ON ur.tenant_id=tu.tenant_id AND ur.user_id=tu.user_id
        JOIN role_permission rp ON rp.tenant_id=ur.tenant_id AND rp.role_id=ur.role_id
        WHERE tu.tenant_id=#{tenantId} AND tu.user_id=#{userId} AND tu.status='ACTIVE' AND u.status='ACTIVE'
        ORDER BY rp.permission_code FOR SHARE
        """
    )
    List<String> currentUserPermissions(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId
    );

    @Select(
        """
        SELECT tu.user_id FROM tenant_user tu
        JOIN sys_user u ON u.id=tu.user_id
        JOIN user_role ur ON ur.tenant_id=tu.tenant_id AND ur.user_id=tu.user_id
        JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
        WHERE tu.tenant_id=#{tenantId} AND tu.status='ACTIVE' AND u.status='ACTIVE' AND r.code='TENANT_ADMIN'
        FOR UPDATE
        """
    )
    List<Long> lockActiveAdminUsers(@Param("tenantId") long tenantId);

    @Select("SELECT id FROM sys_role WHERE tenant_id = #{tenantId} AND code = #{code}")
    Long findRoleIdByCode(@Param("tenantId") long tenantId, @Param("code") String code);

    @Insert(
        "INSERT INTO user_role(tenant_id, user_id, role_id) VALUES(#{tenantId}, #{userId}, #{roleId})"
    )
    int insertUserRole(
        @Param("tenantId") long tenantId,
        @Param("userId") long userId,
        @Param("roleId") long roleId
    );

    @Delete("DELETE FROM user_role WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    int deleteUserRole(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Insert(
        "INSERT INTO role_permission(tenant_id, role_id, permission_code) VALUES(#{tenantId}, #{roleId}, #{permissionCode})"
    )
    int insertRolePermission(
        @Param("tenantId") long tenantId,
        @Param("roleId") long roleId,
        @Param("permissionCode") String permissionCode
    );

    @Insert(
        "INSERT INTO role_permission(tenant_id, role_id, permission_code) SELECT #{tenantId}, #{roleId}, code FROM permission"
    )
    int grantAllPermissions(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    @Select(
        """
            SELECT COUNT(DISTINCT tu.user_id)
            FROM tenant_user tu
            JOIN sys_user u ON u.id = tu.user_id
            JOIN user_role ur ON ur.tenant_id = tu.tenant_id AND ur.user_id = tu.user_id
            JOIN sys_role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE tu.tenant_id = #{tenantId} AND tu.status = 'ACTIVE' AND u.status = 'ACTIVE' AND r.code = 'TENANT_ADMIN'
        """
    )
    long countActiveAdmins(@Param("tenantId") long tenantId);

    @Select(
        """
            SELECT COUNT(*)
            FROM tenant_user tu
            JOIN user_role ur ON ur.tenant_id = tu.tenant_id AND ur.user_id = tu.user_id
            JOIN sys_role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE tu.tenant_id = #{tenantId} AND tu.user_id = #{userId} AND tu.status = 'ACTIVE' AND r.code = 'TENANT_ADMIN'
        """
    )
    long isUserAdmin(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Select(
        """
            SELECT CAST(u.id AS CHAR) AS id, u.username, u.display_name AS name, tu.status, r.code AS role
            FROM tenant_user tu
            JOIN sys_user u ON u.id = tu.user_id
            LEFT JOIN user_role ur ON ur.tenant_id = tu.tenant_id AND ur.user_id = tu.user_id
            LEFT JOIN sys_role r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE tu.tenant_id = #{tenantId}
            ORDER BY u.id
        """
    )
    List<Map<String, Object>> listTenantUsersWithRole(@Param("tenantId") long tenantId);
}
