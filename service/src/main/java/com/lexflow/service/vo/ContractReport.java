package com.lexflow.service.vo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 合同审查报告，保存条款原文、风险及其依据之间的关联。
 * {@link #validate()} 检查报告内部一致性；原件归属、摘要和依据有效性仍由业务服务核验。
 */
public record ContractReport(
    String sourceSha256,
    String sourceText,
    String contractType,
    List<Clause> clauses,
    List<Risk> risks,
    List<String> missingMaterials,
    List<Evidence> evidence,
    boolean needsHuman,
    List<BasisConflict> basisConflicts
) {
    public ContractReport {
        // 兼容未返回依据冲突字段的旧结果，并固定冲突列表，避免校验后被外部修改。
        basisConflicts = basisConflicts == null ? List.of() : List.copyOf(basisConflicts);
    }

    /** 兼容尚未提供依据冲突信息的调用方。 */
    public ContractReport(
        String sha,
        String text,
        String type,
        List<Clause> clauses,
        List<Risk> risks,
        List<String> missing,
        List<Evidence> evidence,
        boolean human
    ) {
        this(sha, text, type, clauses, risks, missing, evidence, human, List.of());
    }

    /** 条款位置为 sourceText 中的 UTF-16 字符区间 [startOffset, endOffset)，须逐字匹配 quote。 */
    public record Clause(String id, String title, String quote, int startOffset, int endOffset) {}

    /** clauseId 指向报告内条款，evidenceIds 指向报告内依据，二者不能直接作为数据库授权凭证。 */
    public record Risk(
        String clauseId,
        String level,
        String reason,
        String suggestion,
        List<String> evidenceIds
    ) {}

    /** id 是知识切片标识，text 须由服务层与该切片当前有效的正文再次核对。 */
    public record Evidence(String id, String text) {}

    /** 每项冲突须关联至少两个不同依据，且这些依据已包含在本报告中。 */
    public record BasisConflict(String description, List<String> evidenceIds) {}

    /** 拒绝越界或悬空引用，并要求资料不足、依据冲突及高风险报告进入人工复核。 */
    public void validate() {
        if (
            sourceSha256 == null ||
            !sourceSha256.matches("[a-f0-9]{64}") ||
            sourceText == null ||
            sourceText.isBlank() ||
            sourceText.length() > 40_000 ||
            !text(contractType, 100) ||
            clauses == null ||
            clauses.isEmpty() ||
            clauses.size() > 20 ||
            risks == null ||
            risks.size() > 20 ||
            missingMaterials == null ||
            missingMaterials.size() > 10 ||
            evidence == null ||
            evidence.size() > 10 ||
            basisConflicts.size() > 10
        ) {
            throw new IllegalArgumentException("合同审查结果格式无效");
        }
        Set<String> ids = new HashSet<>(),
            sources = new HashSet<>();
        for (Clause c : clauses) {
            if (
                c == null ||
                c.id() == null ||
                !c.id().matches("[1-9][0-9]?") ||
                !ids.add(c.id()) ||
                !text(c.title(), 100) ||
                !text(c.quote(), 1000) ||
                c.startOffset() < 0 ||
                c.endOffset() <= c.startOffset() ||
                c.endOffset() > sourceText.length() ||
                !sourceText.substring(c.startOffset(), c.endOffset()).equals(c.quote())
            ) {
                throw new IllegalArgumentException("合同条款引用与原文不一致");
            }
        }
        for (Evidence e : evidence) {
            if (
                e == null ||
                e.id() == null ||
                !e.id().matches("[1-9][0-9]{0,18}") ||
                !sources.add(e.id()) ||
                !text(e.text(), 1000)
            ) {
                throw new IllegalArgumentException("合同审查依据无效");
            }
        }
        // needsHuman 可以主动为 true，但不得绕过这些强制人工复核条件。
        boolean mandatory =
            evidence.isEmpty() || !missingMaterials.isEmpty() || !basisConflicts.isEmpty();
        for (var conflict : basisConflicts) {
            if (
                conflict == null ||
                !text(conflict.description(), 1500) ||
                conflict.evidenceIds() == null ||
                conflict.evidenceIds().size() < 2 ||
                conflict.evidenceIds().size() > 10 ||
                new HashSet<>(conflict.evidenceIds()).size() != conflict.evidenceIds().size() ||
                !sources.containsAll(conflict.evidenceIds())
            ) {
                throw new IllegalArgumentException("依据冲突必须关联至少两项真实来源");
            }
        }
        for (String item : missingMaterials) {
            if (!text(item, 500)) {
                throw new IllegalArgumentException("待补材料无效");
            }
        }
        for (Risk r : risks) {
            if (
                r == null ||
                !ids.contains(r.clauseId()) ||
                r.level() == null ||
                !Set.of("HIGH", "MEDIUM", "LOW").contains(r.level()) ||
                !text(r.reason(), 1500) ||
                !text(r.suggestion(), 1500) ||
                r.evidenceIds() == null ||
                r.evidenceIds().size() > 10 ||
                new HashSet<>(r.evidenceIds()).size() != r.evidenceIds().size() ||
                !sources.containsAll(r.evidenceIds())
            ) {
                throw new IllegalArgumentException("合同风险或依据无效");
            }
            mandatory |= "HIGH".equals(r.level());
        }
        if (mandatory && !needsHuman) {
            throw new IllegalArgumentException("缺少依据、材料或高风险结果必须人工复核");
        }
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
