const source = {
  parseTaskId: '31',
  fileVersionId: '21',
  fileId: '11',
  fileName: '合成收货记录.txt',
  fileVersion: 1,
  sha256: 'e'.repeat(64),
  text: '合成案件资料：已交付货物。',
  segments: [{ kind: 'PARAGRAPH', index: 1, startOffset: 0, endOffset: 15 }],
};
source.segments[0].endOffset = source.text.length;
function input(tenant, task, decisionId = null) {
  return {
    tenantId: tenant,
    taskId: task,
    caseId: '41',
    attempt: 0,
    stateVersion: 2,
    background: '合成货物争议背景',
    requirements: '分析交付事实与待核实事项',
    sources: [structuredClone(source)],
    provider: 'test',
    baseUrl: 'https://models.example.test/v1',
    model: 'case-analysis-test',
    configurationRevision: '12345678-1234-1234-1234-123456789abc',
    apiKey: 'synthetic-case-analysis-secret',
    decisionId,
  };
}
function extraction() {
  return {
    facts: [{ statement: '材料记载已交货', citationIds: ['C1'] }],
    pendingVerification: [],
    citations: [{ id: 'C1', parseTaskId: '31', index: 1, quote: source.text }],
  };
}
function assessment(high = false) {
  return {
    issues: [
      {
        title: '交货证明',
        level: high ? 'HIGH' : 'LOW',
        analysis: '仅能确认材料记载',
        suggestion: '核对原始收货凭证',
        citationIds: ['C1'],
      },
    ],
    missingMaterials: [],
    insufficientEvidence: false,
    recommendationBoundary: '仅依据所选合成材料，不能认定真实交付或诉讼结果',
  };
}
module.exports = { source, input, extraction, assessment };
