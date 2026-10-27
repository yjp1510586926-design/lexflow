package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 根据固定文书版本生成草稿；必须确认模型费用。 */
public record SubmitDocumentGenerationDTO(
    @Positive long sourceVersionId,
    @NotBlank @Size(max = 2000) String instructions,
    @NotBlank @Size(max = 128) String idempotencyKey,
    @AssertTrue boolean confirmModelCost
) {}
