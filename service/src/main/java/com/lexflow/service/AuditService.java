package com.lexflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.SysRoleMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务，继承 BaseService<AuditLogModel, AuditLogMapper>。
 * 律所管理员可查看本租户全部审计日志；普通成员只能查看自身操作记录。
 */
@Service
public class AuditService extends BaseService<AuditLogModel, AuditLogMapper> {

    private final SysRoleMapper sysRoleMapper;
    private final IdentityService identities;
    private final ObjectMapper json;

    public AuditService(
        AuditLogMapper mapper,
        SysRoleMapper sysRoleMapper,
        IdentityService identities,
        ObjectMapper json
    ) {
        super(mapper);
        this.sysRoleMapper = sysRoleMapper;
        this.identities = identities;
        this.json = json;
    }

    public record AuditLogView(
        String id,
        String action,
        String objectType,
        String objectId,
        String result,
        String actorId,
        String actorName,
        String traceId,
        String createdAt,
        JsonNode detail
    ) {}

    public record AuditPage(List<AuditLogView> items, long total, int page, int size) {}

    /**
     * 分页查询审计日志。
     * - 管理员：查全租户
     * - 非管理员：只查自身 actor_id
     */
    public AuditPage list(Identity identity, int page, int size, String action) {
        if (
            identity == null ||
            !identities.active(identity) ||
            !identities.hasPermission(identity, "audit:read")
        ) {
            throw new AccessDenied();
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
        if (page < 1) {
            page = 1;
        }

        boolean isAdmin = sysRoleMapper.isUserAdmin(identity.tenantId(), identity.userId()) > 0;
        Long actorFilter = isAdmin ? null : identity.userId();
        String actionFilter = action != null && !action.isBlank() ? action.trim() : null;

        long offset = (long) (page - 1) * size;
        long total = mapper.countAuditLogs(identity.tenantId(), actorFilter, actionFilter);
        List<Map<String, Object>> rows = mapper.listAuditLogs(
            identity.tenantId(),
            actorFilter,
            actionFilter,
            size,
            offset
        );

        List<AuditLogView> items = rows
            .stream()
            .map(auditRow ->
                new AuditLogView(
                    stringValue(auditRow, "id"),
                    stringValue(auditRow, "action"),
                    stringValue(auditRow, "object_type"),
                    stringValue(auditRow, "object_id"),
                    stringValue(auditRow, "result"),
                    stringValue(auditRow, "actor_id"),
                    stringValue(auditRow, "actor_name"),
                    stringValue(auditRow, "trace_id"),
                    stringValue(auditRow, "created_at"),
                    detail(auditRow)
                )
            )
            .toList();

        return new AuditPage(items, total, page, size);
    }

    private static String stringValue(Map<String, Object> auditRow, String key) {
        Object value = auditRow.get(key);
        return value != null ? value.toString() : null;
    }

    private JsonNode detail(Map<String, Object> row) {
        String text = stringValue(row, "detail_json");
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("审计记录格式无效");
        }
    }
}
