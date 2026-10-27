package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 新增文书版本时提交编辑开始时的并发基准。
 * expectedVersion 是文书记录修订号，expectedCurrentVersionId 是当前正文版本 ID；冲突时须重新确认基准。
 */
public record AddDocumentVersionDTO(
    @NotNull String content,
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Positive Long expectedCurrentVersionId
) {}
