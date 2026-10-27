package com.lexflow.website.biz.dto;

/** 测试指定配置修订；请求标识用于连接测试幂等处理。 */
public record TestAiConfigurationDTO(String revision, String requestId) {}
