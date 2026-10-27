package com.lexflow.website.biz;

import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.AiConversationService;
import com.lexflow.service.AiConversationService.Evidence;
import com.lexflow.service.AiConversationService.QuestionView;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.core.client.LegalQuestionClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 编排问答检索与模型调用，依据在发送给模型和发布回答前均须保持有效。 */
@Service
public class AiConversationBiz {

    public static final String INSUFFICIENT =
        "当前案件资料不足以回答这个问题，请补充相关材料后再提问。";
    public static final String TENANT_INSUFFICIENT =
        "当前律所知识库资料不足以回答这个问题，请补充相关材料后再提问。";
    private final AiConversationService conversations;
    private final AiConfigurationService configs;
    private final KnowledgeSearchBiz searches;
    private final LegalQuestionClient client;

    public AiConversationBiz(
        AiConversationService conversations,
        AiConfigurationService configs,
        KnowledgeSearchBiz searches,
        LegalQuestionClient client
    ) {
        this.conversations = conversations;
        this.configs = configs;
        this.searches = searches;
        this.client = client;
    }

    /** 同一请求标识沿用已登记的问题结果；只有新取得的执行资格才可发起模型调用。 */
    public QuestionView ask(Identity actor, long sessionId, String requestId, String question) {
        var claim = conversations.begin(actor, sessionId, requestId, question);
        if (!claim.fresh()) {
            return conversations.question(actor, sessionId, requestId);
        }
        String insufficient = "TENANT".equals(claim.session().scope())
            ? TENANT_INSUFFICIENT
            : INSUFFICIENT;
        try {
            // 先确认 LLM 配置可用，避免检索已产生 Embedding 费用后才发现无法回答。
            var model = configs.llmForQuestion(actor.tenantId());
            var history = conversations
                .history(actor, sessionId)
                .stream()
                .filter(q -> "SUCCEEDED".equals(q.status()) && !q.sourcesUnavailable())
                .toList();
            var previous = history
                .stream()
                .skip(Math.max(0, history.size() - 6))
                .map(QuestionView::question)
                .toList();
            String query = question.trim();
            if (!previous.isEmpty()) {
                String combined = previous.getLast() + "\n" + query;
                if (combined.length() <= 500) {
                    query = combined;
                }
            }
            var evidence = evidence(
                searches.searchForQuestion(actor, claim.session().baseId(), query, false)
            );
            // 检索期间材料或权限可能变化，向 LLM 发送正文前再次核验来源。
            if (!conversations.available(actor, claim.session(), evidence)) {
                throw BusinessException.conflict("资料状态已变化，请重新提问");
            }
            LegalQuestionClient.Answer answer = evidence.isEmpty()
                ? new LegalQuestionClient.Answer(insufficient, List.of(), true)
                : client.answer(model, question.trim(), previous, evidence);
            if (answer.insufficientEvidence()) {
                // 仅明确的资料不足结果可扩大一次检索；超时或异常不触发重复模型调用。
                if (!conversations.available(actor, claim.session(), evidence)) {
                    throw BusinessException.conflict("资料状态已变化，请重新提问");
                }
                var broader = evidence(
                    searches.searchForQuestion(actor, claim.session().baseId(), query, true)
                );
                if (!conversations.available(actor, claim.session(), broader)) {
                    throw BusinessException.conflict("资料状态已变化，请重新提问");
                }
                if (!broader.isEmpty() && !broader.equals(evidence)) {
                    if (!model.equals(configs.llmForQuestion(actor.tenantId()))) {
                        throw BusinessException.conflict("模型配置已变化，请重新提问");
                    }
                    answer = client.answer(model, question.trim(), previous, broader);
                }
                evidence = broader;
            }
            if (answer.insufficientEvidence()) {
                answer = new LegalQuestionClient.Answer(insufficient, List.of(), true);
            } else {
                validateReferences(answer, evidence);
            }
            conversations.finish(
                actor,
                claim,
                answer.answer(),
                answer.insufficientEvidence(),
                evidence,
                answer.citationIds()
            );
            return conversations.question(actor, sessionId, requestId);
        } catch (RuntimeException ex) {
            conversations.fail(actor, claim);
            throw ex;
        }
    }

    /** 按真实切片去重，再生成本次回答内连续的引用编号，供模型和正文标记共同使用。 */
    private List<Evidence> evidence(KnowledgeSearchBiz.SearchResult found) {
        List<Evidence> evidence = new ArrayList<>();
        Set<String> chunks = new HashSet<>();
        for (var hit : found.citations()) {
            if (chunks.add(hit.chunkId())) {
                evidence.add(
                    new Evidence(
                        Integer.toString(evidence.size() + 1),
                        hit.chunkId(),
                        hit.documentId(),
                        hit.fileId(),
                        hit.fileVersionId(),
                        hit.fileName(),
                        hit.fileVersion(),
                        hit.locatorKind(),
                        hit.locatorIndex(),
                        hit.startOffset(),
                        hit.endOffset(),
                        hit.text()
                    )
                );
            }
        }
        return evidence;
    }

    /** 正文 [编号]、返回的引用清单和真实检索依据须对应，防止凭空引用或悬空编号。 */
    private void validateReferences(LegalQuestionClient.Answer answer, List<Evidence> evidence) {
        Set<String> markers = new HashSet<>();
        var matcher = Pattern.compile("\\[([0-9]+)\\]").matcher(answer.answer());
        while (matcher.find()) {
            markers.add(matcher.group(1));
        }
        Set<String> ids = new HashSet<>(answer.citationIds());
        if (
            ids.isEmpty() ||
            ids.size() != answer.citationIds().size() ||
            !markers.equals(ids) ||
            ids.stream().anyMatch(id -> evidence.stream().noneMatch(e -> e.id().equals(id)))
        ) {
            throw BusinessException.conflict("模型回答包含无效引用，请重新提问");
        }
    }
}
