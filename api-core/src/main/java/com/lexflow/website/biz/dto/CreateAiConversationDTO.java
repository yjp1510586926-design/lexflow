package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 在指定知识库下创建会话。 */
public record CreateAiConversationDTO(@Positive long baseId, @Size(max = 100) String title) {}
