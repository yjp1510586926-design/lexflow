package com.lexflow.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;

/** 调用方仅可传入明确的权限或成员关系变更快照；本工具只序列化，不负责脱敏，勿传请求 DTO 或材料正文。 */
public final class AuditDetails {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditDetails() {}

    public static String change(Object before, Object after) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("before", before);
        detail.put("after", after);
        try {
            return JSON.writeValueAsString(detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法保存权限变更审计");
        }
    }
}
