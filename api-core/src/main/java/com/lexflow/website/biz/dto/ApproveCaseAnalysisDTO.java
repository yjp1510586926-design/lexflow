package com.lexflow.website.biz.dto;

/** 人工审批案件分析结果，携带预期状态版本和决策标识。 */
public record ApproveCaseAnalysisDTO(int stateVersion, String decisionId, String note) {}
