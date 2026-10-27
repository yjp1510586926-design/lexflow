package com.lexflow.website.biz.dto;

/** 人工审批合同审查结果；stateVersion 防止处理过期状态。 */
public record ApproveContractReviewDTO(int stateVersion, String decisionId, String note) {}
