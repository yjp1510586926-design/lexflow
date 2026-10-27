package com.lexflow.website.biz.dto;

import java.util.List;

/** 追加合同审查材料；费用确认、执行版本和请求标识按原流程校验。 */
public record SupplementContractReviewDTO(
    int stateVersion,
    String requestId,
    List<Long> parseTaskIds,
    String instructions,
    boolean costConfirmed
) {}
