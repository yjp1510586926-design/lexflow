package com.lexflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AiConversationRepository;
import com.lexflow.dao.AiConversationRepository.*;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提问登记与结果保存使用短事务，模型调用在事务外执行，并在交付回答时复查来源。 */
@Service
public class AiConversationService {

    public record SessionView(
        String id,
        String caseId,
        String baseId,
        String title,
        String createdAt,
        String scope
    ) {}

    public record Evidence(
        String id,
        String chunkId,
        String documentId,
        String fileId,
        String fileVersionId,
        String fileName,
        int fileVersion,
        String locatorKind,
        int locatorIndex,
        int startOffset,
        int endOffset,
        String text
    ) {}

    public record QuestionView(
        String id,
        String requestId,
        String question,
        String status,
        String answer,
        boolean insufficientEvidence,
        List<Evidence> citations,
        boolean sourcesUnavailable,
        String error,
        String createdAt
    ) {}

    /** 提问登记结果；fresh=false 表示复用旧记录，调用方应读取已有状态而非再次调用模型。 */
    public record Claim(Conversation session, long questionId, boolean fresh) {}

    private final AiConversationRepository conversations;
    private final KnowledgeRepository knowledge;
    private final KnowledgeAccessService access;
    private final AuditLogMapper audits;
    private final ObjectMapper json;

    public AiConversationService(
        AiConversationRepository conversations,
        KnowledgeRepository knowledge,
        KnowledgeAccessService access,
        AuditLogMapper audits,
        ObjectMapper json
    ) {
        this.conversations = conversations;
        this.knowledge = knowledge;
        this.access = access;
        this.audits = audits;
        this.json = json;
    }

    private KnowledgeRepository.BaseRow boundBase(Identity actor, Conversation session) {
        var base = access.query(actor, session.baseId());
        if (
            !Objects.equals(session.caseId(), base.caseId()) ||
            !session.scope().equals(base.scope())
        ) {
            throw new AccessDenied();
        }
        return base;
    }

    public Conversation session(Identity actor, long id) {
        return session(actor, id, false);
    }

    private Conversation session(Identity actor, long id, boolean lock) {
        if (actor == null) {
            throw new AccessDenied();
        }
        var session = conversations.get(actor.tenantId(), id, lock);
        if (session == null || session.owner() != actor.userId()) {
            throw new AccessDenied();
        }
        boundBase(actor, session);
        return session;
    }

    @Transactional
    public SessionView create(Identity actor, long baseId, String title) {
        var base = access.query(actor, baseId);
        String name =
            title == null || title.isBlank()
                ? "TENANT".equals(base.scope())
                    ? "律所资料问答"
                    : "案件资料问答"
                : title.trim();
        if (name.length() > 100) {
            throw BusinessException.badRequest("会话名称最多 100 个字符");
        }
        long id = conversations.create(
            actor.tenantId(),
            base.caseId(),
            baseId,
            actor.userId(),
            name,
            base.scope()
        );
        return toSessionView(session(actor, id));
    }

    public List<SessionView> list(Identity actor, long baseId) {
        var base = access.query(actor, baseId);
        return conversations
            .list(actor.tenantId(), baseId, actor.userId())
            .stream()
            .map(conversation -> {
                if (
                    !Objects.equals(conversation.caseId(), base.caseId()) ||
                    !conversation.scope().equals(base.scope())
                ) {
                    throw new AccessDenied();
                }
                return toSessionView(conversation);
            })
            .toList();
    }

    private SessionView toSessionView(Conversation conversation) {
        return new SessionView(
            Long.toString(conversation.id()),
            conversation.caseId() == null ? null : Long.toString(conversation.caseId()),
            Long.toString(conversation.baseId()),
            conversation.title(),
            conversation.createdAt(),
            conversation.scope()
        );
    }

    /**
     * 按会话锁串行登记提问，同一请求标识只能对应同一问题。
     * 清理超时记录后仍有 PROCESSING 问题时拒绝新提问，防止同一会话并行消费模型费用。
     */
    @Transactional
    public Claim begin(Identity actor, long id, String requestId, String question) {
        var conversation = session(actor, id, true);
        if (
            requestId == null ||
            !requestId.matches(
                "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"
            ) ||
            question == null ||
            question.trim().length() < 2 ||
            question.length() > 500
        ) {
            throw BusinessException.badRequest("请填写 2–500 字的问题与有效请求标识");
        }
        conversations.expire(actor.tenantId(), id);
        var old = conversations.byRequest(actor.tenantId(), id, requestId);
        if (old != null) {
            if (!old.question().equals(question.trim())) {
                throw BusinessException.conflict("同一请求标识不能用于不同问题");
            }
            return new Claim(conversation, old.id(), false);
        }
        // fresh=false 的 Claim 仅供复用记录；会话内同一时刻只允许一个正在执行的问题。
        var history = conversations.history(actor.tenantId(), id);
        if (history.size() >= 100) {
            throw BusinessException.conflict("本会话已有 100 次提问，请新建会话");
        }
        if (
            history
                .stream()
                .anyMatch(questionRecord -> "PROCESSING".equals(questionRecord.status()))
        ) {
            throw BusinessException.conflict("当前会话仍有问题处理中，请稍后刷新记录");
        }
        return new Claim(
            conversation,
            conversations.begin(actor.tenantId(), id, requestId, question.trim()),
            true
        );
    }

    @Transactional
    public List<QuestionView> history(Identity actor, long id) {
        var conversation = session(actor, id, true);
        conversations.expire(actor.tenantId(), id);
        return conversations
            .history(actor.tenantId(), id)
            .stream()
            .map(questionRecord -> questionView(actor, conversation, questionRecord))
            .toList();
    }

    public QuestionView question(Identity actor, long id, String requestId) {
        var conversation = session(actor, id);
        var row = conversations.byRequest(actor.tenantId(), id, requestId);
        if (row == null) {
            throw BusinessException.badRequest("提问记录不存在");
        }
        return questionView(actor, conversation, row);
    }

    private QuestionView questionView(
        Identity actor,
        Conversation conversation,
        Question questionRecord
    ) {
        List<Evidence> evidence = readEvidence(questionRecord.evidenceJson());
        // 历史成功回答仍依赖当时全部材料的可用性，来源失效后同时隐藏答案和引用。
        boolean hidden =
            "SUCCEEDED".equals(questionRecord.status()) &&
            !available(actor, conversation, evidence);
        Set<String> ids = readIds(questionRecord.citationIdsJson());
        return new QuestionView(
            Long.toString(questionRecord.id()),
            questionRecord.requestId(),
            questionRecord.question(),
            questionRecord.status(),
            hidden ? null : questionRecord.answer(),
            questionRecord.insufficient(),
            hidden
                ? List.of()
                : evidence
                      .stream()
                      .filter(evidenceItem -> ids.contains(evidenceItem.id()))
                      .toList(),
            hidden,
            questionRecord.error(),
            questionRecord.createdAt()
        );
    }

    /** 逐条比对当前可引用材料的文件版本、正文及定位；只保留切片 ID 不能证明来源仍有效。 */
    public boolean available(Identity actor, Conversation conversation, List<Evidence> evidence) {
        boundBase(actor, conversation);
        for (Evidence evidenceItem : evidence) {
            var row = knowledge.liveCitation(
                actor.tenantId(),
                conversation.baseId(),
                conversation.caseId(),
                Long.parseLong(evidenceItem.chunkId())
            );
            if (
                row == null ||
                row.documentId() != Long.parseLong(evidenceItem.documentId()) ||
                row.fileId() != Long.parseLong(evidenceItem.fileId()) ||
                row.fileVersionId() != Long.parseLong(evidenceItem.fileVersionId()) ||
                !row.text().equals(evidenceItem.text()) ||
                row.startOffset() != evidenceItem.startOffset() ||
                row.endOffset() != evidenceItem.endOffset() ||
                row.locatorIndex() != evidenceItem.locatorIndex() ||
                !row.locatorKind().equals(evidenceItem.locatorKind())
            ) {
                return false;
            }
        }
        return true;
    }

    /**
     * 保存模型回答前重新验证来源和引用集合；回答只能引用本次提供的证据。
     * 已超时或已结束的提问不再接受结果，避免迟到响应覆盖新的会话状态。
     */
    @Transactional
    public void finish(
        Identity actor,
        Claim claim,
        String answer,
        boolean insufficient,
        List<Evidence> evidence,
        List<String> ids
    ) {
        var conversation = session(actor, claim.session().id(), true);
        if (!available(actor, conversation, evidence)) {
            throw BusinessException.conflict("资料已删除或索引已失效，请重新提问");
        }
        if (
            answer == null ||
            answer.isBlank() ||
            answer.length() > 12000 ||
            evidence.size() > 10 ||
            ids.size() > 10 ||
            (!insufficient && ids.isEmpty()) ||
            new HashSet<>(ids).size() != ids.size() ||
            ids
                .stream()
                .anyMatch(id ->
                    evidence.stream().noneMatch(evidenceItem -> evidenceItem.id().equals(id))
                )
        ) {
            throw BusinessException.conflict("模型回答缺少有效引用，请重新提问");
        }
        String evidenceJson, idsJson;
        try {
            evidenceJson = json.writeValueAsString(evidence);
            idsJson = json.writeValueAsString(ids);
        } catch (Exception ex) {
            throw new IllegalStateException("问答记录序列化失败");
        }
        if (
            conversations.finish(
                actor.tenantId(),
                conversation.id(),
                claim.questionId(),
                answer,
                insufficient,
                evidenceJson,
                idsJson
            ) != 1
        ) {
            throw BusinessException.conflict("提问已超时或结束，请刷新记录");
        }
        audits.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action("ai:question")
                .objectType("ai_question")
                .objectId(Long.toString(claim.questionId()))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson("{\"citations\":" + ids.size() + "}")
                .build()
        );
    }

    @Transactional
    public void fail(Identity actor, Claim claim) {
        // 只使用服务端登记的 Claim 结束本人的提问，错误详情不包含模型服务输出。
        conversations.fail(
            actor.tenantId(),
            claim.session().id(),
            claim.questionId(),
            "回答未完成，请检查模型配置与资料状态后重新提问"
        );
    }

    private List<Evidence> readEvidence(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return json.readValue(value, new TypeReference<List<Evidence>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("问答来源记录无效");
        }
    }

    private Set<String> readIds(String value) {
        if (value == null) {
            return Set.of();
        }
        try {
            return new HashSet<>(json.readValue(value, new TypeReference<List<String>>() {}));
        } catch (Exception ex) {
            throw new IllegalStateException("问答引用记录无效");
        }
    }
}
