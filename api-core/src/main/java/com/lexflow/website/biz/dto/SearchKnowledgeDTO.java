package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 检索知识库原文片段的问题参数。 */
public record SearchKnowledgeDTO(@NotBlank @Size(min = 2, max = 500) String question) {}
