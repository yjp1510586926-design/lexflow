package com.lexflow.service.vo;

import java.util.*;

/**
 * 案件分析报告，将事实和争议点关联到提交时选定的材料引用。
 * 本对象校验报告内部引用；服务层继续核验文件版本、摘要、原文和真实页段。
 */
public record CaseAnalysisReport(
    List<Fact> facts,
    List<String> pendingVerification,
    List<Issue> issues,
    List<String> missingMaterials,
    List<Citation> citations,
    String recommendationBoundary,
    boolean insufficientEvidence,
    boolean needsHuman
) {
    /** 事实必须附带本报告内至少一个引用标识，不能仅以模型陈述作为来源。 */
    public record Fact(String statement, List<String> citationIds) {}

    /** 分析及建议同样须关联引用；HIGH 级别会触发强制人工复核。 */
    public record Issue(
        String title,
        String level,
        String analysis,
        String suggestion,
        List<String> citationIds
    ) {}

    /**
     * parseTaskId 定位解析结果，fileVersionId 和 sha256 固定材料版本。
     * kind/index 定位页或段；偏移是解析正文中的 UTF-16 区间 [startOffset, endOffset)。
     */
    public record Citation(
        String id,
        String parseTaskId,
        String fileVersionId,
        String sha256,
        String kind,
        int index,
        int startOffset,
        int endOffset,
        String quote
    ) {}

    /** 约束报告规模、引用关系和复核标志，不代替租户权限与原材料一致性检查。 */
    public void validate() {
        if (
            facts == null ||
            facts.size() > 30 ||
            pendingVerification == null ||
            pendingVerification.size() > 20 ||
            issues == null ||
            issues.size() > 20 ||
            missingMaterials == null ||
            missingMaterials.size() > 20 ||
            citations == null ||
            citations.size() > 60 ||
            !text(recommendationBoundary, 2000)
        ) {
            invalid();
        }
        var ids = new HashSet<String>();
        for (var c : citations) {
            if (
                c == null ||
                !text(c.id(), 80) ||
                !ids.add(c.id()) ||
                !id(c.parseTaskId()) ||
                !id(c.fileVersionId()) ||
                c.sha256() == null ||
                !c.sha256().matches("[a-f0-9]{64}") ||
                !Set.of("PAGE", "PARAGRAPH").contains(c.kind()) ||
                c.index() < 1 ||
                c.startOffset() < 0 ||
                c.endOffset() <= c.startOffset() ||
                !text(c.quote(), 2000) ||
                c.endOffset() - c.startOffset() != c.quote().length()
            ) {
                invalid();
            }
        }
        for (var f : facts) {
            if (f == null || !text(f.statement(), 1500) || !refs(f.citationIds(), ids, true)) {
                invalid();
            }
        }
        for (var issue : issues) {
            if (
                issue == null ||
                !text(issue.title(), 200) ||
                !Set.of("LOW", "MEDIUM", "HIGH").contains(issue.level()) ||
                !text(issue.analysis(), 2000) ||
                !text(issue.suggestion(), 2000) ||
                !refs(issue.citationIds(), ids, true)
            ) {
                invalid();
            }
        }
        if (
            pendingVerification.stream().anyMatch(s -> !text(s, 1000)) ||
            missingMaterials.stream().anyMatch(s -> !text(s, 1000))
        ) {
            invalid();
        }
        // 缺事实、待核实事项、缺材料或高风险时，即使模型未要求，也必须人工复核。
        boolean required =
            insufficientEvidence ||
            facts.isEmpty() ||
            !pendingVerification.isEmpty() ||
            !missingMaterials.isEmpty() ||
            issues.stream().anyMatch(i -> "HIGH".equals(i.level()));
        if (required && !needsHuman) {
            invalid();
        }
    }

    private static boolean refs(List<String> refs, Set<String> ids, boolean required) {
        return (
            refs != null &&
            (!required || !refs.isEmpty()) &&
            refs.size() <= 20 &&
            new HashSet<>(refs).size() == refs.size() &&
            ids.containsAll(refs)
        );
    }

    private static boolean id(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static void invalid() {
        throw new IllegalArgumentException("案件分析报告格式或引用无效");
    }
}
