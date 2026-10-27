package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.KnowledgeRepository;
import com.lexflow.dao.KnowledgeRepository.*;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.service.AiConfigurationService;
import com.lexflow.service.CaseService;
import com.lexflow.service.identity.Identity;
import com.lexflow.service.permission.AccessDenied;
import com.lexflow.website.biz.KnowledgeSearchBiz;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class KnowledgeRerankTest {

    final Identity actor = new Identity(1, 7, 0, "Tester");
    final ObjectMapper json = new ObjectMapper();
    final KnowledgeRepository knowledge = mock(KnowledgeRepository.class);
    final CaseService cases = mock(CaseService.class);
    final AiConfigurationService configs = mock(AiConfigurationService.class);
    final AuditLogMapper audit = mock(AuditLogMapper.class);
    final AtomicInteger rankCalls = new AtomicInteger();
    final AtomicBoolean deleted = new AtomicBoolean();
    final AtomicBoolean revoked = new AtomicBoolean();
    volatile String rankBody =
        "{\"ranks\":[{\"index\":1,\"score\":0.9},{\"index\":0,\"score\":0.2}]}";
    volatile String received;
    volatile boolean deleteDuringRank, revokeDuringRank, revokeDuringSearch;
    final AtomicInteger searchCalls = new AtomicInteger();
    volatile String searchBody;
    volatile List<Map<String, Object>> customHits;
    volatile boolean overflowHits;
    HttpServer server;
    KnowledgeSearchBiz search;

    @BeforeEach
    void setup() throws Exception {
        when(knowledge.base(7, 9)).thenReturn(new BaseRow(9, 8, "Base", "ACTIVE"));
        var model = new AiConfigurationService.InternalModel(
            "fake",
            "https://models.example.test/v1",
            "embed",
            "embed-key"
        );
        when(configs.embeddingForTask(7)).thenReturn(model);
        when(configs.rerankForSearch(7)).thenReturn(
            new AiConfigurationService.InternalModel(
                "fake",
                "https://models.example.test/v2",
                "rank",
                "rank-key"
            )
        );
        when(knowledge.searchSources(7, 9, 8L)).thenReturn(
            List.of(
                new SearchSourceRow(
                    10,
                    1,
                    "collection",
                    4,
                    json.writeValueAsString(
                        List.of(model.provider(), model.baseUrl(), model.model())
                    )
                )
            )
        );
        for (int index = 0; index < 2; index++) {
            long chunk = 11 + index;
            String text = index == 0 ? "七日内交付。" : "验收后付款。";
            var source = new CitationRow(
                chunk,
                10,
                21,
                31,
                "contract.txt",
                1,
                "PARAGRAPH",
                index + 1,
                0,
                text.length(),
                text
            );
            when(knowledge.citation(7, 9, 8L, 10, chunk, "collection", 1)).thenReturn(source);
            when(knowledge.liveCitation(7, 9, 8L, chunk)).thenAnswer(call ->
                deleted.get() ? null : source
            );
        }
        doAnswer(call -> {
            if (revoked.get()) throw new AccessDenied();
            return null;
        })
            .when(cases)
            .authorize(any(), anyLong(), any(), anyString(), anyBoolean());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/knowledge/search", exchange -> {
            searchCalls.incrementAndGet();
            searchBody = new String(
                exchange.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
            );
            if (revokeDuringSearch) revoked.set(true);
            String result = revokeDuringSearch
                ? "{\"hits\":[]}"
                : customHits == null
                  ? "{\"hits\":[{\"chunkId\":\"11\",\"documentId\":\"10\",\"score\":0.95},{\"chunkId\":\"12\",\"documentId\":\"10\",\"score\":0.8}]}"
                  : json.writeValueAsString(
                        Map.of(
                            "hits",
                            customHits
                                .stream()
                                .limit(
                                    overflowHits
                                        ? customHits.size()
                                        : json.readTree(searchBody).path("limit").asInt()
                                )
                                .toList()
                        )
                    );
            byte[] response = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/internal/v1/knowledge/rerank", exchange -> {
            rankCalls.incrementAndGet();
            received = new String(
                exchange.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
            );
            if (deleteDuringRank) deleted.set(true);
            if (revokeDuringRank) revoked.set(true);
            byte[] response = rankBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        search = new KnowledgeSearchBiz(
            knowledge,
            cases,
            configs,
            audit,
            json,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "test-internal",
            new com.lexflow.service.KnowledgeAccessService(
                knowledge,
                cases,
                mock(com.lexflow.service.IdentityService.class)
            )
        );
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void revokedPublicQueryDoesNotCallTheNextIndexGroupAfterAnEmptyFirstResponse()
        throws Exception {
        when(knowledge.base(7, 9)).thenReturn(new BaseRow(9, null, "Public", "ACTIVE", "TENANT"));
        String identity = json.writeValueAsString(
            List.of("fake", "https://models.example.test/v1", "embed")
        );
        when(knowledge.searchSources(7, 9, null)).thenReturn(
            List.of(
                new SearchSourceRow(10, 1, "collection", 4, identity),
                new SearchSourceRow(20, 2, "collection", 4, identity)
            )
        );
        doAnswer(call -> {
            if (revoked.get()) throw new AccessDenied();
            return null;
        })
            .when(cases)
            .require(any(), anyString());
        revokeDuringSearch = true;
        assertThrows(AccessDenied.class, () -> search.search(actor, 9, "何时付款？"));
        assertEquals(1, searchCalls.get());
        assertEquals(0, rankCalls.get());
        assertEquals("TENANT", json.readTree(searchBody).path("scope").asText());
        assertTrue(json.readTree(searchBody).path("caseId").isNull());
        verifyNoInteractions(audit);
    }

    @Test
    void rankOrderUsesOnlyAuthorizedCandidatesAndManualKey() throws Exception {
        var result = search.search(actor, 9, "何时付款？");
        assertTrue(result.reranked());
        assertEquals("12", result.citations().getFirst().chunkId());
        assertEquals(0.9, result.citations().getFirst().score());
        assertEquals(
            List.of("七日内交付。", "验收后付款。"),
            json.readValue(json.readTree(received).path("documents").toString(), List.class)
        );
        assertEquals("rank-key", json.readTree(received).path("apiKey").asText());
        assertFalse(json.writeValueAsString(result).contains("rank-key"));
        when(configs.rerankForSearch(7)).thenReturn(null);
        var vector = search.search(actor, 9, "何时付款？");
        assertFalse(vector.reranked());
        assertEquals("11", vector.citations().getFirst().chunkId());
        assertEquals(1, rankCalls.get());
    }

    @Test
    void deletionOrRevocationDuringRankingCannotReturnSource() {
        deleteDuringRank = true;
        assertTrue(search.search(actor, 9, "何时付款？").citations().isEmpty());
        deleted.set(false);
        deleteDuringRank = false;
        revokeDuringRank = true;
        assertThrows(AccessDenied.class, () -> search.search(actor, 9, "何时付款？"));
    }

    @Test
    void invalidProviderIndexesNeverFallbackToUnsafelyRankedAnswer() {
        for (String response : List.of(
            "{\"ranks\":[{\"index\":0,\"score\":0.9},{\"index\":0,\"score\":0.2}]}",
            "{\"ranks\":[{\"index\":99,\"score\":0.9},{\"index\":0,\"score\":0.2}]}",
            "{\"ranks\":[{\"index\":1,\"score\":\"0.9\"},{\"index\":0,\"score\":0.2}]}",
            "{\"ranks\":[]}"
        )) {
            rankBody = response;
            assertThrows(BusinessException.class, () -> search.search(actor, 9, "何时付款？"));
        }
        verifyNoInteractions(audit);
    }

    @Test
    void deletedCandidateIsNeverDisclosedToRankingProvider() {
        deleted.set(true);
        assertTrue(search.search(actor, 9, "何时付款？").citations().isEmpty());
        assertEquals(0, rankCalls.get());
    }

    @Test
    void expansionRaisesTheBoundButKeepsDocumentVersionScopeAndAtMostTenExcerpts()
        throws Exception {
        when(configs.rerankForSearch(7)).thenReturn(null);
        customHits = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            long id = 100 + index;
            String text = "授权原文 " + index;
            var row = new CitationRow(
                id,
                10,
                21,
                31,
                "contract.txt",
                1,
                "PARAGRAPH",
                index + 1,
                0,
                text.length(),
                text
            );
            when(knowledge.citation(7, 9, 8L, 10, id, "collection", 1)).thenReturn(row);
            when(knowledge.liveCitation(7, 9, 8L, id)).thenReturn(row);
            customHits.add(
                Map.of(
                    "chunkId",
                    Long.toString(id),
                    "documentId",
                    "10",
                    "score",
                    1.0 - index * 0.01
                )
            );
        }
        assertEquals(5, search.searchForQuestion(actor, 9, "何时付款？", false).citations().size());
        assertEquals(5, json.readTree(searchBody).path("limit").asInt());
        var expanded = search.searchForQuestion(actor, 9, "何时付款？", true);
        assertEquals(10, expanded.citations().size());
        assertEquals("109", expanded.citations().getLast().chunkId());
        var sent = json.readTree(searchBody);
        assertEquals(50, sent.path("limit").asInt());
        assertEquals("7", sent.path("tenantId").asText());
        assertEquals("8", sent.path("caseId").asText());
        assertEquals("9", sent.path("knowledgeBaseId").asText());
        assertEquals(1, sent.path("indexVersion").asInt());
        assertEquals(
            List.of("10"),
            json.readValue(sent.path("documentIds").toString(), List.class)
        );
        assertEquals(2, searchCalls.get());
        assertEquals(0, rankCalls.get());
        assertEquals(10, search.search(actor, 9, "何时付款？").citations().size());
        assertEquals(10, json.readTree(searchBody).path("limit").asInt());
    }

    @Test
    void expandedSearchRejectsOversizedAndForeignHitsBeforeDisclosure() {
        customHits = new ArrayList<>();
        for (int n = 0; n < 51; n++) customHits.add(
            Map.of("chunkId", Long.toString(100 + n), "documentId", "10", "score", 0.8)
        );
        overflowHits = true;
        assertThrows(IllegalStateException.class, () ->
            search.searchForQuestion(actor, 9, "何时付款？", true)
        );
        customHits = List.of(Map.of("chunkId", "11", "documentId", "999", "score", 0.8));
        assertThrows(IllegalStateException.class, () ->
            search.searchForQuestion(actor, 9, "何时付款？", true)
        );
        verifyNoInteractions(audit);
        assertEquals(0, rankCalls.get());
    }
}
