package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Positive;

/** 按状态版本恢复文书生成任务。 */
public record RecoverDocumentGenerationDTO(@Positive int stateVersion) {}
