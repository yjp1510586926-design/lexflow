package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 修改律所成员状态和角色。 */
public record UpdateOrganizationUserDTO(
    @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
    @NotNull @Pattern(regexp = "TENANT_ADMIN|LAWYER|READER") String role
) {}
