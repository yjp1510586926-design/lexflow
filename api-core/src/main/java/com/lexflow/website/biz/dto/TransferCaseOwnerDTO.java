package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 案件负责人移交参数；version 用于检查并发修改。 */
public record TransferCaseOwnerDTO(@Positive long userId, @PositiveOrZero long version) {}
