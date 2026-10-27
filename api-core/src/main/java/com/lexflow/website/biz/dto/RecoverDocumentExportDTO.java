package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Positive;

/** 按预期状态版本恢复文书导出任务。 */
public record RecoverDocumentExportDTO(@Positive int stateVersion) {}
