package com.lexflow.service;

import static com.lexflow.service.permission.CaseAccessPolicy.allows;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.CaseMapper;
import com.lexflow.dao.CaseMemberMapper;
import com.lexflow.dao.TenantUserMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.CaseModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.service.audit.AuditDetails;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 案件业务入口：集中检查当前身份、操作权限、成员角色和案件状态。
 * 写操作在事务内锁定案件，修改时仍校验客户端读取的版本，避免覆盖并发编辑。
 */
@Service
public class CaseService extends BaseService<CaseModel, CaseMapper> {

    private final CaseMemberMapper caseMemberMapper;
    private final TenantUserMapper tenantUserMapper;
    private final AuditLogMapper auditLogMapper;
    private final IdentityService identities;

    public CaseService(
        CaseMapper mapper,
        CaseMemberMapper caseMemberMapper,
        TenantUserMapper tenantUserMapper,
        AuditLogMapper auditLogMapper,
        IdentityService identities
    ) {
        super(mapper);
        this.caseMemberMapper = caseMemberMapper;
        this.tenantUserMapper = tenantUserMapper;
        this.auditLogMapper = auditLogMapper;
        this.identities = identities;
    }

    /** version 是客户端更新及转交负责人的并发校验值；每次读取后应随下一次修改提交。 */
    public record CaseView(
        String id,
        String caseNo,
        String title,
        String type,
        String description,
        String ownerId,
        String status,
        long version,
        LocalDate filedOn,
        Instant archivedAt
    ) {}

    public record CasePage(List<CaseView> items, long total, int page, int size) {}

    private CaseView toCaseView(CaseModel caseModel) {
        return new CaseView(
            caseModel.getId().toString(),
            caseModel.getCaseNo(),
            caseModel.getTitle(),
            caseModel.getCaseType(),
            caseModel.getDescription(),
            caseModel.getOwnerId() != null ? caseModel.getOwnerId().toString() : null,
            caseModel.getStatus(),
            caseModel.getVersion() != null ? caseModel.getVersion() : 0L,
            caseModel.getFiledOn(),
            caseModel.getArchivedAt()
        );
    }

    /** 只检查当前会话和功能权限；操作具体案件时还须调用 authorize 检查案件范围及角色。 */
    public void require(Identity identity, String permission) {
        if (!identities.active(identity) || !identities.hasPermission(identity, permission)) {
            throw new AccessDenied();
        }
    }

    /**
     * 在租户范围内结合功能权限、成员角色和案件状态判定操作资格。
     * lock=true 会锁定案件行，调用方必须已开启事务，后续写入才能与授权判断保持一致。
     */
    public CaseModel authorize(
        Identity identity,
        long id,
        Action action,
        String permission,
        boolean lock
    ) {
        require(identity, permission);
        // 先按租户限定资源，再读取成员关系；lock=true 的调用方须处于事务内。
        CaseModel caseModel = lock
            ? mapper.selectByIdForUpdate(identity.tenantId(), id)
            : mapper.selectOne(
                  new LambdaQueryWrapper<CaseModel>()
                      .eq(CaseModel::getTenantId, identity.tenantId())
                      .eq(CaseModel::getId, id)
              );
        if (caseModel == null) {
            throw new AccessDenied();
        }

        String memberRoleName = caseMemberMapper.findMemberRole(
            identity.tenantId(),
            id,
            identity.userId()
        );
        Membership role =
            memberRoleName == null ? Membership.NONE : Membership.valueOf(memberRoleName);

        Actor actor = new Actor(
            identity.userId(),
            identity.tenantId(),
            true,
            true,
            identities.admin(identity)
        );
        Resource resource = new Resource(
            identity.tenantId(),
            Status.valueOf(caseModel.getStatus()),
            role
        );
        if (!allows(actor, resource, action, true)) {
            throw new AccessDenied();
        }
        return caseModel;
    }

    public CaseView get(Identity identity, long id) {
        return toCaseView(authorize(identity, id, Action.READ, "case:read", false));
    }

    public CasePage list(
        Identity identity,
        int page,
        int size,
        String keyword,
        String status,
        String type,
        Long ownerId
    ) {
        require(identity, "case:read");
        boolean admin = identities.admin(identity);
        String keywordPattern =
            keyword != null && !keyword.isBlank()
                ? "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
                : null;

        long offset = (long) (page - 1) * size;
        long total = mapper.countCases(
            identity.tenantId(),
            identity.userId(),
            admin,
            keywordPattern,
            status,
            type,
            ownerId
        );
        List<CaseModel> items = mapper.listCases(
            identity.tenantId(),
            identity.userId(),
            admin,
            keywordPattern,
            status,
            type,
            ownerId,
            size,
            offset
        );

        return new CasePage(items.stream().map(this::toCaseView).toList(), total, page, size);
    }

    public List<Map<String, Object>> owners(Identity identity) {
        require(identity, "case:read");
        return mapper.listVisibleOwners(
            identity.tenantId(),
            identity.userId(),
            identities.admin(identity)
        );
    }

    @Transactional
    public CaseView create(
        Identity identity,
        String caseNo,
        String title,
        String type,
        String description
    ) {
        return create(identity, caseNo, title, type, description, null);
    }

    @Transactional
    public CaseView create(
        Identity identity,
        String caseNo,
        String title,
        String type,
        String description,
        LocalDate filedOn
    ) {
        require(identity, "case:create");
        validateFiledOn(filedOn);

        CaseModel model = CaseModel.builder()
            .tenantId(identity.tenantId())
            .caseNo(caseNo)
            .title(title)
            .caseType(type)
            .description(description)
            .ownerId(identity.userId())
            .filedOn(filedOn)
            .status("DRAFT")
            .version(0L)
            .build();
        mapper.insert(model);
        long id = model.getId();

        caseMemberMapper.insertMember(identity.tenantId(), id, identity.userId(), "OWNER");
        audit(
            identity,
            "case:create",
            id,
            AuditDetails.change(
                null,
                new OwnerState(
                    Long.toString(identity.userId()),
                    List.of(new MemberState(Long.toString(identity.userId()), "OWNER"))
                )
            )
        );

        return toCaseView(mapper.selectById(id));
    }

    /** 兼容不修改立案日期的案件更新；状态与并发校验统一交给完整重载处理。 */
    @Transactional
    public CaseView update(
        Identity identity,
        long id,
        long version,
        String title,
        String description,
        String targetStatus
    ) {
        return update(identity, id, version, title, description, targetStatus, false, null);
    }

    /**
     * 按读取时的版本修改案件，并校验状态转换；归档恢复使用单独的 RESTORE 操作资格。
     * changeFiledOn 区分保留立案日期与显式修改，修改时 filedOn=null 表示清空日期。
     */
    @Transactional
    public CaseView update(
        Identity identity,
        long id,
        long version,
        String title,
        String description,
        String targetStatus,
        boolean changeFiledOn,
        LocalDate filedOn
    ) {
        if (changeFiledOn) {
            validateFiledOn(filedOn);
        }
        CaseModel existing = mapper.selectOne(
            new LambdaQueryWrapper<CaseModel>()
                .select(CaseModel::getStatus)
                .eq(CaseModel::getTenantId, identity.tenantId())
                .eq(CaseModel::getId, id)
        );
        if (existing == null) {
            throw new AccessDenied();
        }

        Action action =
            existing.getStatus().equals("ARCHIVED") && targetStatus.equals("ACTIVE")
                ? Action.RESTORE
                : Action.UPDATE;
        CaseModel currentModel = authorize(identity, id, action, "case:update", true);
        String current = currentModel.getStatus();

        boolean legal =
            current.equals(targetStatus) ||
            (current.equals("DRAFT") && targetStatus.equals("ACTIVE")) ||
            (current.equals("ACTIVE") && targetStatus.equals("ARCHIVED")) ||
            (current.equals("ARCHIVED") && targetStatus.equals("ACTIVE"));
        if (!legal) {
            throw new IllegalArgumentException("不允许的案件状态转换");
        }

        int changed = mapper.updateWithOptimisticLock(
            identity.tenantId(),
            id,
            version,
            title,
            description,
            targetStatus,
            changeFiledOn,
            filedOn
        );
        if (changed == 0) {
            throw new ConcurrentModificationException("案件已被修改，请刷新后重试");
        }

        audit(identity, "case:update", id);
        return get(identity, id);
    }

    public List<Map<String, Object>> members(Identity identity, long id) {
        authorize(identity, id, Action.READ, "case:read", false);
        return caseMemberMapper.listMembersWithUser(identity.tenantId(), id);
    }

    @Transactional
    public void addMember(Identity identity, long id, long userId, String role) {
        authorize(identity, id, Action.MANAGE_MEMBERS, "case:member", true);
        if (!Set.of("EDITOR", "READER").contains(role)) {
            throw new IllegalArgumentException("新增成员角色必须为 EDITOR 或 READER");
        }
        long count = tenantUserMapper.countActiveUserInTenant(identity.tenantId(), userId);
        if (count != 1) {
            throw new IllegalArgumentException("成员不是本律所有效用户");
        }
        caseMemberMapper.insertMember(identity.tenantId(), id, userId, role);
        audit(
            identity,
            "case:member:add",
            id,
            AuditDetails.change(null, new MemberState(Long.toString(userId), role))
        );
    }

    @Transactional
    public void removeMember(Identity identity, long id, long userId) {
        CaseModel caseModel = authorize(identity, id, Action.MANAGE_MEMBERS, "case:member", true);
        if (caseModel.getOwnerId() == userId) {
            throw new IllegalArgumentException("不能移除案件负责人，请先转交负责人");
        }
        String before = caseMemberMapper.findMemberRole(identity.tenantId(), id, userId);
        caseMemberMapper.deleteMember(identity.tenantId(), id, userId);
        audit(
            identity,
            "case:member:remove",
            id,
            AuditDetails.change(
                before == null ? null : new MemberState(Long.toString(userId), before),
                null
            )
        );
    }

    @Transactional
    public void changeMemberRole(Identity identity, long id, long userId, String role) {
        CaseModel caseModel = authorize(identity, id, Action.MANAGE_MEMBERS, "case:member", true);
        if (!Set.of("EDITOR", "READER").contains(role)) {
            throw new IllegalArgumentException("无效成员角色");
        }
        if (caseModel.getOwnerId() == userId) {
            throw new IllegalArgumentException("请通过转交操作更换负责人");
        }
        String before = caseMemberMapper.findMemberRole(identity.tenantId(), id, userId);
        if (caseMemberMapper.updateMemberRole(identity.tenantId(), id, userId, role) != 1) {
            throw new IllegalArgumentException("案件成员不存在");
        }
        audit(
            identity,
            "case:member:role",
            id,
            AuditDetails.change(
                new MemberState(Long.toString(userId), before),
                new MemberState(Long.toString(userId), role)
            )
        );
    }

    /** 转交负责人时同步修改 OWNER 成员关系；新负责人须是本租户有效用户，并校验案件版本。 */
    @Transactional
    public CaseView transferOwner(
        Identity identity,
        long id,
        long nextOwner,
        long expectedVersion
    ) {
        CaseModel caseModel = authorize(identity, id, Action.MANAGE_MEMBERS, "case:member", true);
        if (caseModel.getVersion() != expectedVersion) {
            throw new ConcurrentModificationException();
        }
        if (caseModel.getOwnerId() == nextOwner) {
            throw new IllegalArgumentException("新负责人不能与当前负责人相同");
        }
        if (tenantUserMapper.countActiveUserInTenant(identity.tenantId(), nextOwner) != 1) {
            throw new IllegalArgumentException("新负责人不是本律所有效成员");
        }

        OwnerState before = new OwnerState(
            Long.toString(caseModel.getOwnerId()),
            List.of(
                new MemberState(Long.toString(caseModel.getOwnerId()), "OWNER"),
                new MemberState(
                    Long.toString(nextOwner),
                    caseMemberMapper.findMemberRole(identity.tenantId(), id, nextOwner)
                )
            )
        );
        // 案件负责人字段与 OWNER 成员角色同事务切换，原负责人降为 EDITOR。
        caseMemberMapper.updateMemberRole(
            identity.tenantId(),
            id,
            caseModel.getOwnerId(),
            "EDITOR"
        );
        int changed = caseMemberMapper.updateMemberRole(
            identity.tenantId(),
            id,
            nextOwner,
            "OWNER"
        );
        if (changed == 0) {
            caseMemberMapper.insertMember(identity.tenantId(), id, nextOwner, "OWNER");
        }

        mapper.transferOwner(identity.tenantId(), id, nextOwner);
        audit(
            identity,
            "case:owner:transfer",
            id,
            AuditDetails.change(
                before,
                new OwnerState(
                    Long.toString(nextOwner),
                    List.of(
                        new MemberState(Long.toString(caseModel.getOwnerId()), "EDITOR"),
                        new MemberState(Long.toString(nextOwner), "OWNER")
                    )
                )
            )
        );
        return get(identity, id);
    }

    public List<Map<String, Object>> tenantUsers(Identity identity) {
        require(identity, "case:member");
        return tenantUserMapper.listActiveTenantUsers(identity.tenantId());
    }

    private static void validateFiledOn(LocalDate date) {
        if (date != null && (date.getYear() < 1000 || date.getYear() > 9999)) {
            throw new IllegalArgumentException("立案日期须为有效的公历日期");
        }
    }

    private void audit(Identity identity, String action, long id) {
        audit(identity, action, id, null);
    }

    private record MemberState(String userId, String role) {}

    private record OwnerState(String ownerId, List<MemberState> members) {}

    private void audit(Identity identity, String action, long id, String detail) {
        auditLogMapper.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action(action)
                .objectType("case")
                .objectId(Long.toString(id))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson(detail)
                .build()
        );
    }
}
