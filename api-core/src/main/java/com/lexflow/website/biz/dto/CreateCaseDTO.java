package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 新建案件参数，立案日期允许为空。 */
public record CreateCaseDTO(
    @NotBlank @Size(max = 100) String caseNo,
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 64) String type,
    @Size(max = 10000) String description,
    LocalDate filedOn
) {}
