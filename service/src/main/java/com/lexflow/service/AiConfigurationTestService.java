package com.lexflow.service;

import com.lexflow.dao.AiConfigurationMapper;
import com.lexflow.dao.AiConfigurationTestRepository;
import com.lexflow.dao.AiConfigurationTestRepository.Row;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.ai.AiCredentialCipher;
import com.lexflow.service.identity.Identity;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 短事务登记调用意图与结果；外部 HTTP 必须在事务之外执行。 */
@Service
public class AiConfigurationTestService {

    public record View(
        String id,
        String purpose,
        String revision,
        String status,
        String message,
        Integer elapsedMs,
        Integer dimension,
        Instant createdAt
    ) {}

    public record Claim(
        long id,
        Identity actor,
        String purpose,
        String revision,
        AiConfigurationService.InternalModel model
    ) {
        @Override
        public String toString() {
            return "AiConfigurationTestClaim[redacted]";
        }
    }

    public record Outcome(String status, int elapsedMs, Integer dimension, String errorCode) {}

    private final AiConfigurationMapper configs;
    private final AiCredentialCipher cipher;
    private final AiConfigurationTestRepository tests;
    private final IdentityService identities;
    private final AuditLogMapper audits;

    public AiConfigurationTestService(
        AiConfigurationMapper configs,
        AiCredentialCipher cipher,
        AiConfigurationTestRepository tests,
        IdentityService identities,
        AuditLogMapper audits
    ) {
        this.configs = configs;
        this.cipher = cipher;
        this.tests = tests;
        this.identities = identities;
        this.audits = audits;
    }

    private void authorize(Identity actor) {
        if (!identities.active(actor) || !identities.admin(actor)) {
            throw BusinessException.forbidden("仅律所管理员可测试 AI 配置");
        }
    }

    private static void purpose(String value) {
        if (value == null || !value.matches("LLM|EMBEDDING|RERANK")) {
            throw BusinessException.badRequest("不支持的模型用途");
        }
    }

    private static void uuid(String value) {
        if (
            value == null ||
            !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        ) {
            throw BusinessException.badRequest("配置版本和请求标识须为 UUID");
        }
    }

    @Transactional
    public View prepare(Identity actor, String purpose, String revision, String request) {
        authorize(actor);
        purpose(purpose);
        uuid(revision);
        uuid(request);
        var config = configs.lockInternal(actor.tenantId(), purpose);
        Row existing = tests.byRequest(actor.tenantId(), request);
        if (existing != null) {
            if (
                existing.creator() != actor.userId() ||
                existing.sessionVersion() != actor.sessionVersion() ||
                !purpose.equals(existing.purpose()) ||
                !revision.equals(existing.revision())
            ) {
                throw BusinessException.conflict("该请求标识已用于其他连接测试");
            }
            tests.expire(actor.tenantId(), purpose);
            return toTestView(tests.get(actor.tenantId(), existing.id(), true), config);
        }
        if (config == null || !revision.equals(config.get("revision"))) {
            throw BusinessException.conflict("配置已变更，请刷新后再测试");
        }
        if (!cipher.configured()) {
            throw new BusinessException(503, "请先配置 AI 凭据加密主密钥");
        }
        tests.expire(actor.tenantId(), purpose);
        // 每用途只允许一个活动测试，并限制提交频率；重复请求只复用原测试，不新增模型调用。
        if (tests.active(actor.tenantId(), purpose) > 0) {
            throw BusinessException.conflict("当前用途已有连接测试正在执行");
        }
        if (tests.recent(actor.tenantId(), purpose) >= 3) {
            throw new BusinessException(429, "连接测试过于频繁，请一分钟后再试");
        }
        long id = tests.create(
            actor.tenantId(),
            actor.userId(),
            actor.sessionVersion(),
            purpose,
            revision,
            request
        );
        audit(actor, id, "ai:config:test:start");
        return toTestView(tests.get(actor.tenantId(), id, true), config);
    }

    @Transactional
    public Claim begin(Identity actor, long id) {
        authorize(actor);
        // 各阶段统一先锁配置再锁测试，且同一请求必须仍属于原创建者、会话版本和配置修订。
        Row snapshot = tests.get(actor.tenantId(), id, false);
        if (snapshot == null) {
            throw BusinessException.notFound("连接测试不存在");
        }
        var config = configs.lockInternal(actor.tenantId(), snapshot.purpose());
        Row row = tests.get(actor.tenantId(), id, true);
        tests.expire(actor.tenantId(), row.purpose());
        if (row.creator() != actor.userId() || row.sessionVersion() != actor.sessionVersion()) {
            return null;
        }
        if (config == null || !row.revision().equals(config.get("revision"))) {
            return null;
        }
        if (!"PREPARED".equals(tests.get(actor.tenantId(), id, true).status())) {
            return null;
        }
        // 解密失败发生在 PROCESSING 提交之前；不会发送请求，也不留下不确定调用。
        var model = new AiConfigurationService.InternalModel(
            (String) config.get("provider"),
            (String) config.get("baseUrl"),
            (String) config.get("model"),
            cipher.decrypt(actor.tenantId(), row.purpose(), (String) config.get("encryptedKey"))
        );
        if (tests.begin(actor.tenantId(), id) != 1) {
            return null;
        }
        return new Claim(id, actor, row.purpose(), row.revision(), model);
    }

    @Transactional
    public void finish(Claim claim, Outcome outcome) {
        var config = configs.lockInternal(claim.actor().tenantId(), claim.purpose());
        Row row = tests.get(claim.actor().tenantId(), claim.id(), true);
        if (row == null || !"PROCESSING".equals(row.status())) {
            return;
        }
        tests.expire(claim.actor().tenantId(), claim.purpose());
        // HTTP 已在事务外完成；结果写回前再检查权限和配置，旧配置的成功不能用于当前配置。
        String status = outcome.status();
        Integer elapsed = outcome.elapsedMs(),
            dimension = outcome.dimension();
        String error = outcome.errorCode();
        if (!identities.active(claim.actor()) || !identities.admin(claim.actor())) {
            status = "FAILED";
            elapsed = null;
            dimension = null;
            error = "ACCESS_REVOKED";
        } else if (config == null || !claim.revision().equals(config.get("revision"))) {
            status = "STALE";
            elapsed = null;
            dimension = null;
            error = "CONFIG_CHANGED";
        }
        if ("PROCESSING".equals(status)) {
            return;
        }
        if (!status.matches("SUCCEEDED|FAILED|UNKNOWN|STALE")) {
            throw new IllegalArgumentException("Invalid test result");
        }
        if (
            tests.finish(claim.actor().tenantId(), claim.id(), status, elapsed, dimension, error) ==
            1
        ) {
            audit(claim.actor(), claim.id(), "ai:config:test:result");
        }
    }

    @Transactional
    public View get(Identity actor, long id) {
        authorize(actor);
        Row snapshot = tests.get(actor.tenantId(), id, false);
        if (snapshot == null) {
            throw BusinessException.notFound("连接测试不存在");
        }
        var config = configs.lockInternal(actor.tenantId(), snapshot.purpose());
        tests.expire(actor.tenantId(), snapshot.purpose());
        return toTestView(tests.get(actor.tenantId(), id, true), config);
    }

    @Transactional
    public View latest(Identity actor, String purpose, String revision) {
        authorize(actor);
        purpose(purpose);
        uuid(revision);
        var config = configs.lockInternal(actor.tenantId(), purpose);
        tests.expire(actor.tenantId(), purpose);
        Row row = tests.latest(actor.tenantId(), purpose, revision);
        return row == null ? null : toTestView(row, config);
    }

    private View toTestView(Row row, Map<String, Object> config) {
        boolean stale = config == null || !row.revision().equals(config.get("revision"));
        String status = stale ? "STALE" : row.status();
        String message = switch (status) {
            case "SUCCEEDED" -> "连接测试成功；仅验证接口可用，不代表业务回答质量";
            case "FAILED" -> "连接测试失败，请检查 Key、模型名称和部署者允许的接口地址";
            // 超时等不确定结果可能已计费，保留 UNKNOWN，调用方不得据此自动重发。
            case "UNKNOWN" -> "未获得确定结果，可能已经产生调用费用；不会自动重试";
            case "STALE" -> "配置已变更，此测试结果已失效";
            default -> "连接测试正在执行，请稍候";
        };
        return new View(
            Long.toString(row.id()),
            row.purpose(),
            row.revision(),
            status,
            message,
            stale ? null : row.elapsedMs(),
            stale ? null : row.dimension(),
            row.createdAt()
        );
    }

    private void audit(Identity actor, long id, String action) {
        audits.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action(action)
                .objectType("ai_configuration_test")
                .objectId(Long.toString(id))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson("{}")
                .build()
        );
    }
}
