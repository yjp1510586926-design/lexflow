package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 人工复核 AI 生成的草稿，结果为通过或驳回。 */
public record ReviewDocumentDTO(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String status) {}
