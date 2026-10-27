package com.lexflow.service;

import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.EvidenceRepository;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.service.permission.CaseAccessPolicy.Action;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceService {

    private final EvidenceRepository repository;
    private final CaseService cases;
    private final AuditLogMapper audits;

    public EvidenceService(
        EvidenceRepository repository,
        CaseService cases,
        AuditLogMapper audits
    ) {
        this.repository = repository;
        this.cases = cases;
        this.audits = audits;
    }

    public record View(
        String id,
        String fileId,
        String fileVersionId,
        String fileName,
        int fileVersion,
        String sha256,
        String source,
        String collectedAt,
        String description,
        int sortOrder,
        long version,
        String createdBy,
        String updatedBy,
        String updatedAt
    ) {}

    public record Page(List<View> items, long total, int page, int size, boolean canEdit) {}

    public Page list(Identity actor, long caseId, int page, int size) {
        cases.authorize(actor, caseId, Action.READ, "file:read", false);
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw BusinessException.badRequest("分页参数无效");
        }
        boolean canEdit = true;
        try {
            cases.authorize(actor, caseId, Action.UPDATE, "file:upload", false);
        } catch (AccessDenied denied) {
            canEdit = false;
        }
        return new Page(
            repository
                .list(actor.tenantId(), caseId, size, (long) (page - 1) * size)
                .stream()
                .map(this::toEvidenceView)
                .toList(),
            repository.count(actor.tenantId(), caseId),
            page,
            size,
            canEdit
        );
    }

    public View get(Identity actor, long caseId, long fileVersionId) {
        cases.authorize(actor, caseId, Action.READ, "file:read", false);
        var row = repository.get(actor.tenantId(), caseId, fileVersionId);
        return row == null ? null : toEvidenceView(row);
    }

    @Transactional
    public View save(
        Identity actor,
        long caseId,
        long fileVersionId,
        long expectedVersion,
        String source,
        OffsetDateTime collectedAt,
        String description,
        int sortOrder
    ) {
        cases.authorize(actor, caseId, Action.UPDATE, "file:upload", true);
        cases.authorize(actor, caseId, Action.READ, "file:read", false);
        if (
            fileVersionId <= 0 ||
            expectedVersion < 0 ||
            source == null ||
            source.isBlank() ||
            source.length() > 500 ||
            description == null ||
            description.length() > 4000 ||
            sortOrder < 0 ||
            sortOrder > 100000 ||
            (collectedAt != null &&
                (collectedAt.toInstant().isBefore(Instant.parse("1000-01-01T00:00:00Z")) ||
                    collectedAt.toInstant().isAfter(Instant.parse("9999-12-31T23:59:59Z"))))
        ) {
            throw BusinessException.badRequest(
                "请填写有效的证据来源和收集时间；说明最多 4000 字，顺序为 0–100000"
            );
        }
        if (repository.lockSource(actor.tenantId(), caseId, fileVersionId) == null) {
            throw new AccessDenied();
        }
        var timestamp = collectedAt == null ? null : Timestamp.from(collectedAt.toInstant());
        // 证据目录绑定不可变文件版本；0 表示首次创建，其余版本用于防止并发编辑覆盖。
        if (expectedVersion == 0) {
            try {
                repository.create(
                    actor.tenantId(),
                    caseId,
                    fileVersionId,
                    source.trim(),
                    timestamp,
                    description.trim(),
                    sortOrder,
                    actor.userId()
                );
            } catch (DuplicateKeyException ex) {
                throw BusinessException.conflict("证据目录已有记录，请刷新后编辑");
            }
        } else if (
            repository.update(
                actor.tenantId(),
                caseId,
                fileVersionId,
                source.trim(),
                timestamp,
                description.trim(),
                sortOrder,
                actor.userId(),
                expectedVersion
            ) != 1
        ) {
            throw BusinessException.conflict("证据目录已被修改，请刷新后重试");
        }
        var saved = repository.get(actor.tenantId(), caseId, fileVersionId);
        audits.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action("evidence:save")
                .objectType("evidence")
                .objectId(saved.get("id").toString())
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson(
                    "{\"fileVersionId\":\"" +
                        fileVersionId +
                        "\",\"version\":" +
                        saved.get("version") +
                        "}"
                )
                .build()
        );
        return toEvidenceView(saved);
    }

    private View toEvidenceView(Map<String, Object> row) {
        return new View(
            str(row, "id"),
            str(row, "file_id"),
            str(row, "file_version_id"),
            str(row, "display_name"),
            ((Number) row.get("version_no")).intValue(),
            str(row, "sha256"),
            str(row, "source"),
            time(row.get("collected_at")),
            str(row, "description"),
            ((Number) row.get("sort_order")).intValue(),
            ((Number) row.get("version")).longValue(),
            str(row, "created_by"),
            str(row, "updated_by"),
            time(row.get("updated_at"))
        );
    }

    private static String str(Map<String, Object> row, String name) {
        return row.get(name) == null ? null : row.get(name).toString();
    }

    private static String time(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp t) {
            return t.toInstant().toString();
        }
        if (value instanceof LocalDateTime t) {
            return t.toInstant(ZoneOffset.UTC).toString();
        }
        throw new IllegalStateException("证据目录时间格式无效");
    }
}
