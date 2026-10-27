package com.lexflow.website.biz.dto;

/** 内部执行开始回调；attempt 与 stateVersion 标识本轮执行。 */
public record BeginCaseAnalysisExecutionDTO(int attempt, int stateVersion) {}
