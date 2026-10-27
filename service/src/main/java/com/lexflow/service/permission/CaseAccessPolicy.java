package com.lexflow.service.permission;

/** 案件权限纯策略；身份、角色和成员关系均须由服务端查询，不能采信请求中的声明。 */
public final class CaseAccessPolicy {

    private CaseAccessPolicy() {}

    public enum Membership {
        OWNER,
        EDITOR,
        READER,
        NONE,
    }

    public enum Action {
        READ,
        UPDATE,
        UPLOAD,
        DELETE_FILE,
        MANAGE_MEMBERS,
        RESTORE,
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        ARCHIVED,
    }

    public record Actor(
        long userId,
        long tenantId,
        boolean enabled,
        boolean tenantMembershipActive,
        boolean tenantAdmin
    ) {}

    public record Resource(long tenantId, Status status, Membership membership) {}

    public static boolean allows(
        Actor actor,
        Resource resource,
        Action action,
        boolean hasPermission
    ) {
        // 管理员也必须满足有效身份、同租户和操作权限，之后才享有负责人级别的案件访问。
        if (
            actor == null ||
            resource == null ||
            action == null ||
            resource.status() == null ||
            resource.membership() == null ||
            !actor.enabled() ||
            !actor.tenantMembershipActive() ||
            actor.tenantId() != resource.tenantId() ||
            !hasPermission
        ) {
            return false;
        }
        boolean owner = actor.tenantAdmin() || resource.membership() == Membership.OWNER;
        boolean reader =
            owner ||
            resource.membership() == Membership.EDITOR ||
            resource.membership() == Membership.READER;
        if (action == Action.READ) {
            return reader;
        }
        // 归档案件保留读取权限；恢复仅允许负责人或管理员，其余写入全部拒绝。
        if (action == Action.RESTORE) {
            return resource.status() == Status.ARCHIVED && owner;
        }
        if (resource.status() == Status.ARCHIVED) {
            return false;
        }
        if (action == Action.MANAGE_MEMBERS) {
            return owner;
        }
        return owner || resource.membership() == Membership.EDITOR;
    }
}
