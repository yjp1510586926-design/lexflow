package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 创建律所用户并指定成员角色。 */
public record CreateOrganizationUserDTO(
    @NotBlank @Size(max = 100) String username,
    @NotNull @Size(min = 12, max = 72) String password,
    @NotBlank @Size(max = 100) String name,
    @NotNull @Pattern(regexp = "TENANT_ADMIN|LAWYER|READER") String role
) {}
