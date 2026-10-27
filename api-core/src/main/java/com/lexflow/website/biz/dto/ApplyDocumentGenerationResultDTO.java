package com.lexflow.website.biz.dto;

import java.util.List;

/** 文书生成结果回调，包含草稿内容与缺失信息。 */
public record ApplyDocumentGenerationResultDTO(
    String tenantId,
    int attempt,
    int stateVersion,
    String outcome,
    String content,
    List<String> missingInformation
) {}
