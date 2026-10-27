package com.lexflow.website.biz.dto;

import com.lexflow.service.vo.ContractReport;

/** 合同审查结果回调，按租户与执行版本校验归属。 */
public record ApplyContractReviewResultDTO(
    String tenantId,
    int attempt,
    int stateVersion,
    String outcome,
    ContractReport report
) {}
