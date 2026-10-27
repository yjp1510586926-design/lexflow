package com.lexflow.website.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.KnowledgeRepository.BaseRow;
import com.lexflow.dao.KnowledgeRepository.CitationRow;
import com.lexflow.dao.KnowledgeRepository.SearchSourceRow;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.framework.context.TraceContext;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.KnowledgeAccessService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.website.core.client.InternalAiServiceUrl;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Java 确定可检索的材料范围；向量命中只提供标识，引用正文由当前有效的数据库记录补全。 */
@Service
public class KnowledgeSearchBiz {

    private final KnowledgeRepository knowledge;
    private final CaseService cases;
    private final KnowledgeAccessService access;
    private final AiConfigurationService configs;
    private final AuditLogMapper audits;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final URI endpoint;
    private final URI rerankEndpoint;
    private final String secret;

    public KnowledgeSearchBiz(
        KnowledgeRepository knowledge,
        CaseService cases,
        AiConfigurationService configs,
        AuditLogMapper audits,
        ObjectMapper json,
        @Value("${lexflow.ai-service-url}") String aiUrl,
        @Value("${lexflow.internal-secret}") String secret,
        KnowledgeAccessService access
    ) {
        this.knowledge = knowledge;
        this.cases = cases;
        this.configs = configs;
        this.audits = audits;
        this.json = json;
        this.secret = secret;
        this.access = access;
        URI base = InternalAiServiceUrl.parse(aiUrl);
        this.endpoint = base.resolve("/internal/v1/knowledge/search");
        this.rerankEndpoint = base.resolve("/internal/v1/knowledge/rerank");
    }

    /** 来自有效材料的切片引用；字符偏移沿用解析正文的 UTF-16 区间 [startOffset, endOffset)。 */
    public record Citation(
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
        String text,
        double score
    ) {}

    public record SearchResult(List<Citation> citations, int outdatedDocuments, boolean reranked) {
        public SearchResult(List<Citation> citations, int outdatedDocuments) {
            this(citations, outdatedDocuments, false);
        }
    }

    private record Group(String collectionName, int dimension, int indexVersion) {}

    public SearchResult search(Identity actor, long baseId, String question) {
        return search(actor, baseId, question, 10, 10, false);
    }

    /** 首轮少量检索，资料不足时扩大候选数量；expanded 不扩大已经授权的知识库范围。 */
    public SearchResult searchForQuestion(
        Identity actor,
        long baseId,
        String question,
        boolean expanded
    ) {
        return search(actor, baseId, question, expanded ? 50 : 5, expanded ? 10 : 5, expanded);
    }

    private SearchResult search(
        Identity actor,
        long baseId,
        String question,
        int candidateLimit,
        int resultLimit,
        boolean expanded
    ) {
        if (actor == null) {
            throw new AccessDenied();
        }
        if (question == null || question.trim().length() < 2 || question.length() > 500) {
            throw BusinessException.badRequest("检索问题须为 2–500 个字符");
        }
        BaseRow base = knowledge.base(actor.tenantId(), baseId);
        if (base == null || !"ACTIVE".equals(base.status())) {
            throw new AccessDenied();
        }
        authorize(actor, base);
        List<SearchSourceRow> sources = knowledge.searchSources(
            actor.tenantId(),
            baseId,
            base.caseId()
        );
        if (sources.size() > 100) {
            throw BusinessException.conflict("当前最多检索 100 份知识材料，请缩小知识库范围");
        }
        if (sources.isEmpty()) {
            return new SearchResult(List.of(), 0);
        }

        var model = configs.embeddingForTask(actor.tenantId());
        String identity;
        try {
            identity = json.writeValueAsString(
                List.of(model.provider().trim(), model.baseUrl().trim(), model.model().trim())
            );
        } catch (Exception ex) {
            throw new IllegalStateException("模型标识无法序列化", ex);
        }
        // 不同 Embedding 模型的向量不能混用；模型已变更的材料仅计入过期提示。
        List<SearchSourceRow> current = sources
            .stream()
            .filter(s -> identity.equals(s.modelIdentity()))
            .toList();
        int outdated = sources.size() - current.size();
        if (current.isEmpty()) {
            throw BusinessException.conflict(
                "索引使用的 Embedding 模型与当前配置不同，请重新建立索引"
            );
        }
        // 同一模型也可能存在不同维度、集合或索引版本，须分组检索再合并。
        Map<Group, List<String>> groups = new LinkedHashMap<>();
        for (SearchSourceRow source : current) {
            Group key = new Group(
                source.collectionName(),
                source.dimension(),
                source.indexVersion()
            );
            groups
                .computeIfAbsent(key, unused -> new ArrayList<>())
                .add(Long.toString(source.documentId()));
        }
        if (groups.size() > 5) {
            throw BusinessException.conflict("索引版本过多，请先整理知识库");
        }

        List<Citation> citations = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        for (var entry : groups.entrySet()) {
            checkDeadline(deadline);
            authorize(actor, base);
            JsonNode hits = remoteSearch(
                actor,
                base,
                question.trim(),
                model,
                entry.getKey(),
                entry.getValue(),
                candidateLimit
            );
            if (!hits.isArray() || hits.size() > candidateLimit) {
                throw new IllegalStateException("向量检索结果格式无效");
            }
            for (JsonNode hit : hits) {
                String chunkId = hit.path("chunkId").asText();
                String documentId = hit.path("documentId").asText();
                double score = hit.path("score").asDouble(Double.NaN);
                if (
                    !validId(chunkId) ||
                    !validId(documentId) ||
                    !Double.isFinite(score) ||
                    !entry.getValue().contains(documentId)
                ) {
                    throw new IllegalStateException("向量检索返回越界结果");
                }
                // 模型调用期间授权或材料可用性可能变化，返回命中后重新授权并读取有效正文。
                authorize(actor, base);
                CitationRow row = knowledge.citation(
                    actor.tenantId(),
                    baseId,
                    base.caseId(),
                    Long.parseLong(documentId),
                    Long.parseLong(chunkId),
                    entry.getKey().collectionName(),
                    entry.getKey().indexVersion()
                );
                if (row != null) {
                    citations.add(
                        new Citation(
                            Long.toString(row.chunkId()),
                            Long.toString(row.documentId()),
                            Long.toString(row.fileId()),
                            Long.toString(row.fileVersionId()),
                            row.fileName(),
                            row.fileVersion(),
                            row.locatorKind(),
                            row.locatorIndex(),
                            row.startOffset(),
                            row.endOffset(),
                            row.text(),
                            score
                        )
                    );
                }
            }
        }
        citations.sort(Comparator.comparingDouble(Citation::score).reversed());
        Map<String, Citation> unique = new LinkedHashMap<>();
        for (var citation : citations) {
            unique.putIfAbsent(citation.chunkId(), citation);
        }
        // 向重排模型发送正文前重查有效性；重排完成后还须再次过滤，避免返回失效引用。
        List<Citation> candidates = unique
            .values()
            .stream()
            .filter(c -> live(actor, base, c))
            .limit(50)
            .toList();
        var rerank = candidates.isEmpty() ? null : configs.rerankForSearch(actor.tenantId());
        boolean reranked = rerank != null;
        if (reranked) {
            checkDeadline(deadline);
        }
        List<Citation> ranked = reranked
            ? remoteRerank(question.trim(), rerank, candidates, resultLimit)
            : candidates.stream().limit(resultLimit).toList();
        List<Citation> top = ranked
            .stream()
            .filter(c -> live(actor, base, c))
            .toList();
        authorize(actor, base);
        audits.insert(
            AuditLogModel.builder()
                .tenantId(actor.tenantId())
                .actorId(actor.userId())
                .action("knowledge:search")
                .objectType("knowledge_base")
                .objectId(Long.toString(baseId))
                .result("SUCCESS")
                .traceId(TraceContext.currentOrNew())
                .detailJson(
                    "{\"citations\":" +
                        top.size() +
                        ",\"reranked\":" +
                        reranked +
                        ",\"expanded\":" +
                        expanded +
                        "}"
                )
                .build()
        );
        return new SearchResult(top, outdated, reranked);
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline) {
            throw BusinessException.conflict("检索已超时，请缩小资料范围或稍后重试");
        }
    }

    private boolean live(Identity actor, BaseRow base, Citation source) {
        authorize(actor, base);
        CitationRow row = knowledge.liveCitation(
            actor.tenantId(),
            base.id(),
            base.caseId(),
            Long.parseLong(source.chunkId())
        );
        return (
            row != null &&
            row.documentId() == Long.parseLong(source.documentId()) &&
            row.fileId() == Long.parseLong(source.fileId()) &&
            row.fileVersionId() == Long.parseLong(source.fileVersionId()) &&
            row.startOffset() == source.startOffset() &&
            row.endOffset() == source.endOffset() &&
            row.locatorIndex() == source.locatorIndex() &&
            row.locatorKind().equals(source.locatorKind()) &&
            row.text().equals(source.text())
        );
    }

    private List<Citation> remoteRerank(
        String question,
        AiConfigurationService.InternalModel model,
        List<Citation> sources,
        int limit
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", model.provider());
        body.put("baseUrl", model.baseUrl());
        body.put("model", model.model());
        body.put("apiKey", model.apiKey());
        body.put("query", question);
        body.put("documents", sources.stream().map(Citation::text).toList());
        body.put("limit", limit);
        try {
            var request = HttpRequest.newBuilder(rerankEndpoint)
                .timeout(Duration.ofSeconds(40))
                .header("X-Internal-Secret", secret)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body),
                        StandardCharsets.UTF_8
                    )
                )
                .build();
            var response = http.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200 || response.body().length() > 100_000) {
                throw BusinessException.conflict("资料排序暂不可用，请检查排序模型配置或稍后重试");
            }
            JsonNode ranks = json.readTree(response.body()).path("ranks");
            if (!ranks.isArray() || ranks.size() != Math.min(limit, sources.size())) {
                throw BusinessException.conflict("资料排序结果无效，请稍后重试");
            }
            Set<Integer> used = new HashSet<>();
            List<Citation> result = new ArrayList<>();
            for (JsonNode rank : ranks) {
                if (
                    !rank.path("index").isIntegralNumber() ||
                    !rank.path("index").canConvertToInt() ||
                    !rank.path("score").isNumber()
                ) {
                    throw BusinessException.conflict("资料排序结果无效，请稍后重试");
                }
                int index = rank.path("index").asInt();
                double score = rank.path("score").asDouble(Double.NaN);
                if (
                    index < 0 ||
                    index >= sources.size() ||
                    !used.add(index) ||
                    !Double.isFinite(score) ||
                    score < 0 ||
                    score > 1
                ) {
                    throw BusinessException.conflict("资料排序结果无效，请稍后重试");
                }
                Citation source = sources.get(index);
                result.add(
                    new Citation(
                        source.chunkId(),
                        source.documentId(),
                        source.fileId(),
                        source.fileVersionId(),
                        source.fileName(),
                        source.fileVersion(),
                        source.locatorKind(),
                        source.locatorIndex(),
                        source.startOffset(),
                        source.endOffset(),
                        source.text(),
                        score
                    )
                );
            }
            result.sort(Comparator.comparingDouble(Citation::score).reversed());
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BusinessException.conflict("资料排序已中断，请稍后重试");
        } catch (IOException ex) {
            throw BusinessException.conflict("资料排序暂不可用，请稍后重试");
        }
    }

    private JsonNode remoteSearch(
        Identity actor,
        BaseRow base,
        String question,
        AiConfigurationService.InternalModel model,
        Group group,
        List<String> documents,
        int limit
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", Long.toString(actor.tenantId()));
        body.put("caseId", base.caseId() == null ? null : Long.toString(base.caseId()));
        body.put("scope", base.scope());
        body.put("knowledgeBaseId", Long.toString(base.id()));
        body.put("documentIds", documents);
        body.put("indexVersion", group.indexVersion());
        body.put("collectionName", group.collectionName());
        body.put("dimension", group.dimension());
        body.put("provider", model.provider());
        body.put("baseUrl", model.baseUrl());
        body.put("model", model.model());
        body.put("apiKey", model.apiKey());
        body.put("query", question);
        body.put("limit", limit);
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(45))
                .header("X-Internal-Secret", secret)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body),
                        StandardCharsets.UTF_8
                    )
                )
                .build();
            HttpResponse<String> response = http.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw BusinessException.conflict("知识检索暂不可用，请检查模型与向量服务");
            }
            if (response.body().length() > 100_000) {
                throw new IllegalStateException("向量检索响应过大");
            }
            return json.readTree(response.body()).path("hits");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("知识检索被中断", ex);
        } catch (IOException ex) {
            throw BusinessException.conflict("知识检索暂不可用，请稍后重试");
        }
    }

    private void authorize(Identity actor, BaseRow base) {
        BaseRow current = access.query(actor, base.id());
        if (
            !Objects.equals(current.caseId(), base.caseId()) ||
            !current.scope().equals(base.scope())
        ) {
            throw new AccessDenied();
        }
    }

    private static boolean validId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
