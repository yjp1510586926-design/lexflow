package com.lexflow.website.biz.dto;

/** 在指定合同审查执行轮次内检索依据片段。 */
public record SearchContractReviewEvidenceDTO(int attempt, int stateVersion, String query) {}
