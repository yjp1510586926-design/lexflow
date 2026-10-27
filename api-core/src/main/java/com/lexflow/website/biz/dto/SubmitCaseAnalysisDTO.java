package com.lexflow.website.biz.dto;

import java.util.List;

/** 提交案件分析，保留材料任务、幂等键和模型费用确认。 */
public record SubmitCaseAnalysisDTO(
    long caseId,
    List<Long> parseTaskIds,
    String background,
    String requirements,
    String idempotencyKey,
    boolean confirmModelCosts
) {}
