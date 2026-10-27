package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;

/** 异步任务提交参数；保留可选案件和资源版本的缺省语义。 */
public record SubmitTaskDTO(
    Long caseId,
    @NotBlank(message = "任务类型不能为空") String type,
    Long resourceVersionId,
    String idempotencyKey
) {}
