package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建文书及初始版本；内容缺省时由控制器使用空字符串。 */
public record CreateDocumentDTO(@NotBlank @Size(max = 255) String title, String content) {}
