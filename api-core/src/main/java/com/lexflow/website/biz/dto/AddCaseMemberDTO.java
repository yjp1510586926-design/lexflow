package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 新增案件成员及其案件内角色。 */
public record AddCaseMemberDTO(
    @Positive long userId,
    @Pattern(regexp = "EDITOR|READER") @NotNull String role
) {}
