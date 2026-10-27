package com.lexflow.website.biz.dto;

import com.lexflow.service.TaskResultService;
import java.util.List;

/**
 * 解析任务结果回调，attempt/stateVersion 用于拒绝过期执行结果。
 * sourceSha256 标识原文件，segments 的偏移基于 parsedText，不能作为原文件的字节偏移使用。
 */
public record ApplyTaskResultDTO(
    int attempt,
    int stateVersion,
    boolean success,
    String resultObjectKey,
    String errorMessage,
    String parsedText,
    String sourceSha256,
    List<TaskResultService.SourceSegment> segments
) {}
