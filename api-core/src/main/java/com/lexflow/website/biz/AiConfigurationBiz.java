package com.lexflow.website.biz;

import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.IdentityService;
import com.lexflow.service.identity.Identity;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 律所管理员管理 AI 配置的入口，配置变更与审计在同一事务内提交。 */
@Service
public class AiConfigurationBiz {

    private final AiConfigurationService configs;
    private final IdentityService identities;
    private final AuditLogMapper audit;

    public AiConfigurationBiz(
        AiConfigurationService configs,
        IdentityService identities,
        AuditLogMapper audit
    ) {
        this.configs = configs;
        this.identities = identities;
        this.audit = audit;
    }

    private void authorize(Identity identity) {
        if (!identities.active(identity) || !identities.admin(identity)) {
            throw BusinessException.forbidden("仅律所管理员可管理 AI 配置");
        }
    }

    public Map<String, Object> list(Identity identity) {
        authorize(identity);
        return Map.of(
            "storageReady",
            configs.storageReady(),
            "items",
            configs.list(identity.tenantId())
        );
    }

    @Transactional
    public void save(
        Identity identity,
        String purpose,
        String provider,
        String url,
        String model,
        String key
    ) {
        authorize(identity);
        configs.save(identity.tenantId(), purpose, provider, url, model, key);
        record(identity, purpose, "ai:config:save");
    }

    @Transactional
    public void delete(Identity identity, String purpose) {
        authorize(identity);
        if (configs.delete(identity.tenantId(), purpose) > 0) {
            record(identity, purpose, "ai:config:delete");
        }
    }

    private void record(Identity identity, String purpose, String action) {
        audit.insert(
            AuditLogModel.builder()
                .tenantId(identity.tenantId())
                .actorId(identity.userId())
                .action(action)
                .objectType("ai_configuration")
                .objectId(purpose)
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson("{}")
                .build()
        );
    }
}
