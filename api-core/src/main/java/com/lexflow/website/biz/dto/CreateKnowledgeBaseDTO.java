package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 创建属于指定案件的知识库。 */
public record CreateKnowledgeBaseDTO(
    @Positive long caseId,
    @NotBlank @Size(max = 200) String name
) {}
