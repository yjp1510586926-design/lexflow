package com.lexflow.service;

import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.SysRoleMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.dao.TenantMapper;
import com.lexflow.dao.TenantUserMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.SysRoleModel;
import com.lexflow.dao.model.SysUserModel;
import com.lexflow.dao.model.TenantModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.audit.AuditDetails;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 律所初始化与成员管理；成员角色变更在租户锁保护下完成，并记录变更前后权限。
 */
@Service
public class OrganizationService {

    private final TenantMapper tenantMapper;
    private final SysUserMapper sysUserMapper;
    private final TenantUserMapper tenantUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final AuditLogMapper auditLogMapper;
    private final IdentityService identities;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private static final Set<String> ROLES = Set.of("TENANT_ADMIN", "LAWYER", "READER");

    public OrganizationService(
        TenantMapper tenantMapper,
        SysUserMapper sysUserMapper,
        TenantUserMapper tenantUserMapper,
        SysRoleMapper sysRoleMapper,
        AuditLogMapper auditLogMapper,
        IdentityService identities
    ) {
        this.tenantMapper = tenantMapper;
        this.sysUserMapper = sysUserMapper;
        this.tenantUserMapper = tenantUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.auditLogMapper = auditLogMapper;
        this.identities = identities;
    }

    @Transactional
    public String initialize(String tenantName, String username, String password) {
        validatePassword(password);
        text(tenantName, 200);
        text(username, 100);

        // installation_guard 的行锁串行化首次安装，防止并发创建多套初始律所数据。
        Boolean initialized = tenantMapper.checkInstallationGuard();
        if (Boolean.TRUE.equals(initialized) || tenantMapper.selectCount(null) > 0) {
            throw new IllegalStateException("已有律所数据，不允许重新初始化");
        }

        TenantModel tenant = TenantModel.builder().name(tenantName).status("ACTIVE").build();
        tenantMapper.insert(tenant);
        long tenantId = tenant.getId();

        SysUserModel user = SysUserModel.builder()
            .username(username)
            .passwordHash(encoder.encode(password))
            .displayName(username)
            .status("ACTIVE")
            .sessionVersion(0L)
            .build();
        sysUserMapper.insert(user);
        long userId = user.getId();

        tenantUserMapper.insertMembership(tenantId, userId, "ACTIVE");

        for (String code : List.of("TENANT_ADMIN", "LAWYER", "READER")) {
            String roleName = code.equals("TENANT_ADMIN")
                ? "律所管理员"
                : code.equals("LAWYER")
                  ? "律师"
                  : "只读成员";
            SysRoleModel role = SysRoleModel.builder()
                .tenantId(tenantId)
                .code(code)
                .name(roleName)
                .build();
            sysRoleMapper.insert(role);
            long roleId = role.getId();

            if (code.equals("TENANT_ADMIN")) {
                sysRoleMapper.grantAllPermissions(tenantId, roleId);
                sysRoleMapper.insertUserRole(tenantId, userId, roleId);
            } else {
                List<String> permissions = code.equals("READER")
                    ? List.of("case:read", "file:read", "document:read", "task:read")
                    : List.of(
                          "case:read",
                          "case:create",
                          "case:update",
                          "case:member",
                          "file:read",
                          "file:upload",
                          "file:delete",
                          "document:read",
                          "document:write",
                          "task:read",
                          "task:cancel",
                          "ai:query",
                          "ai:review",
                          "ai:generate"
                      );
                for (String permission : permissions) {
                    sysRoleMapper.insertRolePermission(tenantId, roleId, permission);
                }
            }
        }

        tenantMapper.markInstalled();
        audit(
            tenantId,
            userId,
            "organization:initialize",
            userId,
            AuditDetails.change(null, membershipState(tenantId, userId))
        );
        return Long.toString(tenantId);
    }

    @Transactional
    public String createUser(
        Identity actor,
        String username,
        String password,
        String name,
        String role
    ) {
        authorize(actor);
        validatePassword(password);
        text(username, 100);
        text(name, 100);
        checkRole(role);

        lockTenant(actor);
        Long roleId = sysRoleMapper.findRoleIdByCode(actor.tenantId(), role);
        if (roleId == null) {
            throw new IllegalArgumentException("角色不存在");
        }

        SysUserModel user = SysUserModel.builder()
            .username(username)
            .passwordHash(encoder.encode(password))
            .displayName(name)
            .status("ACTIVE")
            .sessionVersion(0L)
            .build();
        sysUserMapper.insert(user);
        long id = user.getId();

        tenantUserMapper.insertMembership(actor.tenantId(), id, "ACTIVE");
        sysRoleMapper.insertUserRole(actor.tenantId(), id, roleId);
        audit(
            actor.tenantId(),
            actor.userId(),
            "organization:user:create",
            id,
            AuditDetails.change(null, membershipState(actor.tenantId(), id))
        );
        return Long.toString(id);
    }

    public List<Map<String, Object>> users(Identity actor) {
        authorize(actor);
        return sysRoleMapper.listTenantUsersWithRole(actor.tenantId());
    }

    @Transactional
    public void changeMembership(Identity actor, long userId, String status, String role) {
        authorize(actor);
        checkRole(role);
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new IllegalArgumentException("无效状态");
        }

        lockTenant(actor);
        if (tenantUserMapper.countMembership(actor.tenantId(), userId) != 1) {
            throw new AccessDenied();
        }

        Long targetRoleId = sysRoleMapper.findRoleIdByCode(actor.tenantId(), role);
        if (targetRoleId == null) {
            throw new IllegalArgumentException("目标角色不存在");
        }

        MembershipState before = membershipState(actor.tenantId(), userId);
        boolean wasAdmin = before.roles().contains("TENANT_ADMIN");
        // 同租户的成员变更先取得租户锁，避免并发降级各自看到另一名有效管理员。
        if (wasAdmin && (!status.equals("ACTIVE") || !role.equals("TENANT_ADMIN"))) {
            long count = sysRoleMapper
                .lockActiveAdminUsers(actor.tenantId())
                .stream()
                .distinct()
                .count();
            if (count <= 1) {
                throw new IllegalArgumentException("不能禁用或降级最后一名管理员");
            }
        }

        tenantUserMapper.updateStatus(actor.tenantId(), userId, status);
        sysRoleMapper.deleteUserRole(actor.tenantId(), userId);
        sysRoleMapper.insertUserRole(actor.tenantId(), userId, targetRoleId);
        audit(
            actor.tenantId(),
            actor.userId(),
            "organization:membership:update",
            userId,
            AuditDetails.change(before, membershipState(actor.tenantId(), userId))
        );
    }

    private void authorize(Identity actor) {
        if (
            actor == null ||
            !identities.active(actor) ||
            !identities.admin(actor) ||
            !identities.hasPermission(actor, "tenant:user:manage")
        ) {
            throw new AccessDenied();
        }
    }

    private void lockTenant(Identity actor) {
        tenantMapper.lockTenant(actor.tenantId());
    }

    private record MembershipState(
        String userId,
        String status,
        List<String> roles,
        List<String> permissions
    ) {}

    private MembershipState membershipState(long tenant, long user) {
        String status = tenantUserMapper.lockMembershipStatus(tenant, user);
        return new MembershipState(
            Long.toString(user),
            status,
            sysRoleMapper.currentUserRoles(tenant, user),
            sysRoleMapper.currentUserPermissions(tenant, user).stream().distinct().sorted().toList()
        );
    }

    private void audit(long tenant, long actor, String action, long target, String detail) {
        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(tenant)
                .actorId(actor)
                .action(action)
                .objectType("user")
                .objectId(Long.toString(target))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson(detail)
                .build()
        );
    }

    private static void checkRole(String role) {
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("无效角色");
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("文本长度不正确");
        }
    }

    public static void validatePassword(String password) {
        // BCrypt 以字节处理密码，超出 72 字节的部分无法可靠参与校验。
        if (
            password == null ||
            password.length() < 12 ||
            password.getBytes(StandardCharsets.UTF_8).length > 72
        ) {
            throw new IllegalArgumentException("密码至少12字符，UTF-8编码不得超过72字节");
        }
    }
}
