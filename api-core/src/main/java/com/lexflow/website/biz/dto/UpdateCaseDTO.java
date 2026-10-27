package com.lexflow.website.biz.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 案件修改参数；filedOn 缺失时保留原日期，显式 JSON null 时清空日期。 */
public record UpdateCaseDTO(
    @PositiveOrZero long version,
    @NotBlank @Size(max = 200) String title,
    @Size(max = 10000) String description,
    @Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED") @NotNull String status,
    JsonNode filedOn
) {}
