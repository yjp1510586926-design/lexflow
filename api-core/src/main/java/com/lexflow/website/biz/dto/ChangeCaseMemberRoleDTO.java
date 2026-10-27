package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 修改现有案件成员的编辑或只读权限。 */
public record ChangeCaseMemberRoleDTO(@NotNull @Pattern(regexp = "EDITOR|READER") String role) {}
