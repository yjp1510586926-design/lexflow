package com.lexflow.website.biz.dto;

/** 登记指定执行轮次创建的向量集合。 */
public record RegisterKnowledgeVectorsDTO(
    int attempt,
    int stateVersion,
    String collectionName,
    int dimension,
    String modelIdentity
) {}
