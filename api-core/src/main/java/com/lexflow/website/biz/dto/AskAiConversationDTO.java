package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 提交会话问题，请求标识保留重试时的幂等语义。 */
public record AskAiConversationDTO(
    @NotBlank @Size(min = 36, max = 36) String requestId,
    @NotBlank @Size(min = 2, max = 500) String question
) {}
