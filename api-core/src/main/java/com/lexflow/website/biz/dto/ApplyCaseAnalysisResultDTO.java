package com.lexflow.website.biz.dto;

import com.lexflow.service.vo.CaseAnalysisReport;

/** 案件分析结果回调；保留租户、执行轮次和状态版本。 */
public record ApplyCaseAnalysisResultDTO(
    String tenantId,
    int attempt,
    int stateVersion,
    String outcome,
    CaseAnalysisReport report
) {}
