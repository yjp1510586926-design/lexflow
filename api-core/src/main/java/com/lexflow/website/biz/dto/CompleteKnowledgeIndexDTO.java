package com.lexflow.website.biz.dto;

/** 知识库索引完成回调；可空维度和片段数量保留原语义。 */
public record CompleteKnowledgeIndexDTO(
    int attempt,
    int stateVersion,
    boolean success,
    String collectionName,
    Integer dimension,
    Integer indexedChunks,
    String modelIdentity
) {}
