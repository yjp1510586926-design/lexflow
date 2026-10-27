package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 文书状态变更的并发基准：记录修订号与正文版本 ID 共同定位预期状态，不能只比较其中一项。 */
public record ExpectedDocumentDTO(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Positive Long expectedCurrentVersionId
) {}
