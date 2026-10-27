package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** 保存证据说明，version 用于防止覆盖并发修改。 */
public record SaveEvidenceDTO(
    @PositiveOrZero long version,
    @NotBlank @Size(max = 500) String source,
    OffsetDateTime collectedAt,
    @NotNull @Size(max = 4000) String description,
    @Min(0) @Max(100000) int sortOrder
) {}
