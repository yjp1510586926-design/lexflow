package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Positive;

/** 解析律所知识库的固定文件版本，支持幂等请求。 */
public record ParseTenantKnowledgeFileDTO(@Positive long fileVersionId, String idempotencyKey) {}
