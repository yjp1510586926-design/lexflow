package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 密码登录参数；认证后的身份从服务端会话获取。 */
public record LoginByPasswordDTO(
    @NotBlank @Size(max = 100) String username,
    @NotBlank @Size(max = 200) String password,
    @Positive long tenantId
) {}
