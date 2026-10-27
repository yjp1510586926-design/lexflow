package com.lexflow.website.biz;

import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.SysUserMapper;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.CaseService;
import com.lexflow.service.KnowledgeAccessService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 后台执行沿用当前创建人的材料权限，不能仅凭内部服务凭据绕过案件授权。 */
@Service
public class TaskContextBiz {

    private final AsyncTaskMapper tasks;
    private final SysUserMapper users;
    private final CaseService cases;
    private final KnowledgeAccessService knowledge;

    public TaskContextBiz(
        AsyncTaskMapper tasks,
        SysUserMapper users,
        CaseService cases,
        KnowledgeAccessService knowledge
    ) {
        this.tasks = tasks;
        this.users = users;
        this.cases = cases;
        this.knowledge = knowledge;
    }

    /** 从数据库重建任务创建人的当前身份，再按律所或案件范围授权后返回材料上下文。 */
    public Map<String, Object> get(long taskId) {
        var context = tasks.getTaskContext(taskId);
        if (context == null || context.isEmpty()) {
            throw BusinessException.notFound("任务材料不可用");
        }
        long creator = ((Number) context.get("created_by")).longValue();
        var user = users.selectById(creator);
        if (user == null) {
            throw BusinessException.forbidden("任务创建人已不可用");
        }
        long tenant = ((Number) context.get("tenant_id")).longValue();
        var actor = new Identity(creator, tenant, user.getSessionVersion(), user.getDisplayName());
        if ("TENANT".equals(context.get("scope"))) {
            if (context.get("case_id") != null || context.get("knowledge_base_id") == null) {
                throw BusinessException.forbidden("任务材料归属无效");
            }
            var base = knowledge.manage(
                actor,
                ((Number) context.get("knowledge_base_id")).longValue()
            );
            if (!"TENANT".equals(base.scope()) || base.caseId() != null) {
                throw BusinessException.forbidden("任务材料归属无效");
            }
        } else {
            if (!"CASE".equals(context.get("scope")) || context.get("case_id") == null) {
                throw BusinessException.forbidden("任务材料归属无效");
            }
            long caseId = ((Number) context.get("case_id")).longValue();
            cases.authorize(actor, caseId, Action.READ, "file:read", false);
        }
        return context;
    }
}
