package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.AiConversationService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.AiConversationBiz;
import com.lexflow.website.biz.dto.AskAiConversationDTO;
import com.lexflow.website.biz.dto.CreateAiConversationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 知识库会话入口；会话管理由服务层处理，问答由 Biz 编排检索与模型调用。 */
@RestController
@RequestMapping("/api/v1/ai/sessions")
@Validated
public class AiConversationController {

    private final AiConversationService sessions;
    private final AiConversationBiz questions;

    public AiConversationController(AiConversationService sessions, AiConversationBiz questions) {
        this.sessions = sessions;
        this.questions = questions;
    }

    @PostMapping
    ApiResponse<?> create(
        @AuthenticationPrincipal Identity actor,
        @Valid @RequestBody CreateAiConversationDTO request
    ) {
        return ApiResponse.success(sessions.create(actor, request.baseId(), request.title()));
    }

    @GetMapping
    ApiResponse<?> list(
        @AuthenticationPrincipal Identity actor,
        @RequestParam @Positive long baseId
    ) {
        return ApiResponse.success(sessions.list(actor, baseId));
    }

    @GetMapping("/{id}/messages")
    ApiResponse<?> history(
        @AuthenticationPrincipal Identity actor,
        @PathVariable @Positive long id
    ) {
        return ApiResponse.success(sessions.history(actor, id));
    }

    @PostMapping("/{id}/messages")
    ApiResponse<?> ask(
        @AuthenticationPrincipal Identity actor,
        @PathVariable @Positive long id,
        @Valid @RequestBody AskAiConversationDTO request
    ) {
        return ApiResponse.success(
            questions.ask(actor, id, request.requestId(), request.question())
        );
    }
}
