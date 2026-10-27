package com.lexflow.service.audit;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexflow.service.identity.Identity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** 持久请求审计只记录服务端验证的身份与有限元数据，不写入请求正文、凭据或模型内容。 */
public record RequestAuditEvent(
    String eventId,
    Instant occurredAt,
    Long tenantId,
    Long actorId,
    String action,
    int status,
    String method,
    String route,
    String objectId,
    String traceId
) {
    static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of(
        "eventId",
        "occurredAt",
        "tenantId",
        "actorId",
        "action",
        "status",
        "method",
        "route",
        "objectId",
        "traceId"
    );
    private static final Set<String> METHODS = Set.of(
        "GET",
        "POST",
        "PUT",
        "PATCH",
        "DELETE",
        "HEAD",
        "OPTIONS",
        "OTHER"
    );

    /** 新建事件与离线回放共用校验；时间须匹配数据库微秒精度，身份与路由只接收受限元数据。 */
    public RequestAuditEvent {
        requireUuid(eventId);
        requireUuid(traceId);
        if (
            occurredAt == null ||
            !occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS)) ||
            occurredAt.isBefore(Instant.parse("2000-01-01T00:00:00Z")) ||
            occurredAt.isAfter(Instant.now().plusSeconds(300))
        ) {
            throw invalid();
        }
        // 匿名事件同时省略租户与操作者，不能把未经验证的租户声明附在匿名身份上。
        if (
            (tenantId == null) != (actorId == null) ||
            (tenantId != null && (tenantId <= 0 || actorId <= 0))
        ) {
            throw invalid();
        }
        if (
            !Set.of("auth:login", "auth:logout", "request:failure").contains(action) ||
            status < 100 ||
            status > 599 ||
            !METHODS.contains(method) ||
            route == null ||
            route.length() > 200 ||
            !(route.equals("UNMATCHED") || route.matches("/[/a-zA-Z0-9_{}:*.-]+")) ||
            (objectId != null && !objectId.matches("[0-9]{1,19}"))
        ) {
            throw invalid();
        }
        if (action.equals("request:failure") && status < 400) {
            throw invalid();
        }
        if (
            action.startsWith("auth:") &&
            (!method.equals("POST") || !route.equals("/api/v1/" + action.replace(':', '/')))
        ) {
            throw invalid();
        }
        if (action.equals("auth:login") && status >= 400 && actorId != null) {
            throw invalid();
        }
    }

    public static RequestAuditEvent create(
        Identity actor,
        String action,
        int status,
        String method,
        String route,
        String objectId,
        String traceId
    ) {
        return new RequestAuditEvent(
            UUID.randomUUID().toString(),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            actor == null ? null : actor.tenantId(),
            actor == null ? null : actor.userId(),
            action,
            status,
            method,
            route,
            objectId,
            traceId
        );
    }

    public String result() {
        return status < 400 ? "SUCCESS" : status == 401 || status == 403 ? "DENIED" : "FAILED";
    }

    ObjectNode json() {
        // 固定字段顺序生成摘要和签名，使落库与离线重放使用完全相同的事件字节。
        ObjectNode node = JSON.createObjectNode();
        node.put("eventId", eventId);
        node.put("occurredAt", occurredAt.toString());
        if (tenantId == null) {
            node.putNull("tenantId");
            node.putNull("actorId");
        } else {
            node.put("tenantId", tenantId);
            node.put("actorId", actorId);
        }
        node.put("action", action);
        node.put("status", status);
        node.put("method", method);
        node.put("route", route);
        if (objectId == null) {
            node.putNull("objectId");
        } else {
            node.put("objectId", objectId);
        }
        node.put("traceId", traceId);
        return node;
    }

    byte[] bytes() {
        return json().toString().getBytes(StandardCharsets.UTF_8);
    }

    public String payloadHash() {
        return hash(bytes());
    }

    public String detailJson() {
        return JSON.createObjectNode()
            .put("method", method)
            .put("route", route)
            .put("httpStatus", status)
            .toString();
    }

    static RequestAuditEvent parse(JsonNode node) {
        requireFields(node, FIELDS);
        if (!node.get("status").isIntegralNumber() || !node.get("status").canConvertToInt()) {
            throw invalid();
        }
        return new RequestAuditEvent(
            string(node, "eventId"),
            Instant.parse(string(node, "occurredAt")),
            id(node, "tenantId"),
            id(node, "actorId"),
            string(node, "action"),
            node.get("status").intValue(),
            string(node, "method"),
            string(node, "route"),
            nullableString(node, "objectId"),
            string(node, "traceId")
        );
    }

    static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) {
            throw invalid();
        }
        node.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) {
                throw invalid();
            }
        });
    }

    static String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String nullableString(JsonNode node, String name) {
        return node.get(name).isNull() ? null : string(node, name);
    }

    private static Long id(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    static void requireUuid(String id) {
        if (
            id == null ||
            !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
            !UUID.fromString(id).toString().equals(id)
        ) {
            throw invalid();
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Audit digest unavailable");
        }
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid request audit metadata");
    }
}
