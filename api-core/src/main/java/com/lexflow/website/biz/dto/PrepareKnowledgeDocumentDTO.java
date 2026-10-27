package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Positive;

/** 使用解析任务结果准备知识库文档片段。 */
public record PrepareKnowledgeDocumentDTO(@Positive long parseTaskId) {}
