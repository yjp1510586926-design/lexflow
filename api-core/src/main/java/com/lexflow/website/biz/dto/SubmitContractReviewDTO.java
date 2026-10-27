package com.lexflow.website.biz.dto;

/** 提交合同审查，绑定原件版本与请求幂等键。 */
public record SubmitContractReviewDTO(
    long caseId,
    long fileVersionId,
    String stance,
    String focus,
    Long knowledgeBaseId,
    String idempotencyKey
) {}
