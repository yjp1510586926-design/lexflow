package com.lexflow.service;

import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.KnowledgeRepository.BaseRow;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import org.springframework.stereotype.Service;

/** 统一来源范围权限；空 caseId 本身永远不是授权依据。 */
@Service
public class KnowledgeAccessService {

    private final KnowledgeRepository knowledge;
    private final CaseService cases;
    private final IdentityService identities;

    public KnowledgeAccessService(
        KnowledgeRepository knowledge,
        CaseService cases,
        IdentityService identities
    ) {
        this.knowledge = knowledge;
        this.cases = cases;
        this.identities = identities;
    }

    public BaseRow read(Identity actor, long baseId) {
        BaseRow base = base(actor, baseId);
        if ("CASE".equals(base.scope()) && base.caseId() != null) {
            cases.authorize(actor, base.caseId(), Action.READ, "case:read", false);
            cases.authorize(actor, base.caseId(), Action.READ, "file:read", false);
        } else if ("TENANT".equals(base.scope()) && base.caseId() == null) {
            cases.require(actor, "knowledge:read");
        } else {
            throw new AccessDenied();
        }
        return base;
    }

    public BaseRow manage(Identity actor, long baseId) {
        BaseRow base = read(actor, baseId);
        if ("TENANT".equals(base.scope())) {
            requireTenantManager(actor);
        } else {
            cases.authorize(actor, base.caseId(), Action.UPDATE, "knowledge:manage", true);
        }
        return base;
    }

    public BaseRow query(Identity actor, long baseId) {
        BaseRow base = read(actor, baseId);
        if ("TENANT".equals(base.scope())) {
            cases.require(actor, "ai:query");
        } else {
            cases.authorize(actor, base.caseId(), Action.READ, "ai:query", false);
        }
        return base;
    }

    public boolean canManageTenant(Identity actor) {
        // 公共知识管理同时要求有效身份、律所管理员角色以及读/管理权限。
        return (
            identities.active(actor) &&
            identities.admin(actor) &&
            identities.hasPermission(actor, "knowledge:manage") &&
            identities.hasPermission(actor, "knowledge:read")
        );
    }

    public void requireTenantManager(Identity actor) {
        if (!canManageTenant(actor)) {
            throw new AccessDenied();
        }
    }

    private BaseRow base(Identity actor, long id) {
        if (actor == null) {
            throw new AccessDenied();
        }
        BaseRow base = knowledge.base(actor.tenantId(), id);
        if (base == null || !"ACTIVE".equals(base.status())) {
            throw new AccessDenied();
        }
        return base;
    }
}
