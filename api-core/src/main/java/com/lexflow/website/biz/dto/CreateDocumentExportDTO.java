package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 按固定文书版本提交导出任务，幂等键避免重复创建。 */
public record CreateDocumentExportDTO(
    @NotNull @Positive Long expectedVersionId,
    @NotBlank @Size(max = 128) String idempotencyKey
) {}
