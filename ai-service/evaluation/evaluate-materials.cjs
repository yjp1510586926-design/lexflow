#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');

const MAX_INPUT_BYTES = 5 * 1024 * 1024;
const NUMBER_CHARACTER =
  /[0-9０-９〇零一二三四五六七八九十百千万亿两廿卅壹贰叁肆伍陆柒捌玖拾佰仟萬億貮]/u;
const SAFE_IDENTIFIER = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$/;
const CITATION_IDENTIFIER = /^[1-9][0-9]{0,17}$/;

function readJson(filename) {
  const fd = fs.openSync(filename, 'r');
  try {
    const stat = fs.fstatSync(fd);
    if (!stat.isFile() || stat.size > MAX_INPUT_BYTES)
      throw new Error('评估输入必须为不超过5MiB的文件');
    const buffer = Buffer.alloc(MAX_INPUT_BYTES + 1);
    let length = 0;
    while (length < buffer.length) {
      const count = fs.readSync(fd, buffer, length, buffer.length - length, null);
      if (!count) break;
      length += count;
    }
    if (length > MAX_INPUT_BYTES) throw new Error('评估输入超过5MiB');
    const bytes = buffer.subarray(0, length);
    return {
      value: JSON.parse(bytes.toString('utf8')),
      sha256: createHash('sha256').update(bytes).digest('hex'),
    };
  } finally {
    fs.closeSync(fd);
  }
}
function record(value, required, optional = []) {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    [Object.prototype, null].includes(Object.getPrototypeOf(value)) &&
    required.every((key) => Object.hasOwn(value, key)) &&
    Object.keys(value).every((key) => required.includes(key) || optional.includes(key))
  );
}
function boundedText(value, maximum) {
  return (
    typeof value === 'string' &&
    value.trim().length > 0 &&
    value.length <= maximum &&
    !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value)
  );
}
function identifier(value) {
  return typeof value === 'string' && SAFE_IDENTIFIER.test(value);
}
function citationIdentifier(value) {
  return typeof value === 'string' && CITATION_IDENTIFIER.test(value);
}
function arrayOf(value, maximum, predicate) {
  return Array.isArray(value) && value.length <= maximum && value.every(predicate);
}
function unique(value) {
  return value.length === new Set(value).size;
}
function validateDataset(dataset) {
  if (
    !record(dataset, ['schemaVersion', 'corpusId', 'cases'], ['scope', 'license']) ||
    dataset.schemaVersion !== 1 ||
    !identifier(dataset.corpusId) ||
    !arrayOf(dataset.cases, 1000, (value) => record(value, ['id', 'input', 'expected'])) ||
    !dataset.cases.length ||
    ['scope', 'license'].some(
      (key) => Object.hasOwn(dataset, key) && !boundedText(dataset[key], 2000),
    )
  )
    throw new Error('评估数据格式无效');
  const ids = new Set();
  for (const item of dataset.cases) {
    const input = item.input;
    const expected = item.expected;
    if (
      !identifier(item.id) ||
      ids.has(item.id) ||
      !record(input, ['question', 'previousQuestions', 'evidence']) ||
      !boundedText(input.question, 4000) ||
      !arrayOf(input.previousQuestions, 50, (value) => boundedText(value, 4000)) ||
      !arrayOf(
        input.evidence,
        10,
        (value) =>
          record(value, ['id', 'text']) &&
          citationIdentifier(value.id) &&
          boundedText(value.text, 40000),
      ) ||
      !unique(input.evidence.map((value) => value.id)) ||
      !record(
        expected,
        ['citationIds', 'insufficientEvidence', 'requiredFacts', 'forbiddenText'],
        ['refusalAnswer'],
      ) ||
      !arrayOf(expected.citationIds, 10, citationIdentifier) ||
      !unique(expected.citationIds) ||
      !expected.citationIds.every((id) => input.evidence.some((value) => value.id === id)) ||
      typeof expected.insufficientEvidence !== 'boolean' ||
      !arrayOf(
        expected.requiredFacts,
        50,
        (group) =>
          arrayOf(group, 10, (value) => boundedText(value, 1000)) &&
          group.length > 0 &&
          unique(group),
      ) ||
      !arrayOf(expected.forbiddenText, 50, (value) => boundedText(value, 1000)) ||
      !unique(expected.forbiddenText) ||
      (expected.insufficientEvidence
        ? !boundedText(expected.refusalAnswer, 12000) ||
          expected.citationIds.length > 0 ||
          expected.requiredFacts.length > 0
        : Object.hasOwn(expected, 'refusalAnswer') || !expected.citationIds.length)
    )
      throw new Error('固定评估项格式无效');
    ids.add(item.id);
  }
  return ids;
}
function validateResults(results, ids) {
  if (
    !record(results, ['schemaVersion', 'cases'], ['source', 'realProviderCalls']) ||
    results.schemaVersion !== 1 ||
    !arrayOf(results.cases, 1000, (value) => record(value, ['id', 'output'])) ||
    (Object.hasOwn(results, 'source') && !boundedText(results.source, 2000)) ||
    (Object.hasOwn(results, 'realProviderCalls') &&
      (!Number.isSafeInteger(results.realProviderCalls) ||
        results.realProviderCalls < 0 ||
        results.realProviderCalls > 1000000000))
  )
    throw new Error('答案数据格式无效');
  const answers = new Map();
  for (const item of results.cases) {
    if (!identifier(item.id) || !ids.has(item.id) || answers.has(item.id))
      throw new Error('答案包含未知或重复评估标识');
    answers.set(item.id, item.output);
  }
  return answers;
}

// Keywords remain a finite check. Numeric fragments must be complete tokens:
// "12万元" cannot match "112万元", and "十日" cannot match "三十日".
function hasRequiredFact(answer, fact) {
  let offset = -1;
  while ((offset = answer.indexOf(fact, offset + 1)) !== -1) {
    let valid = true;
    for (let index = 0; index < fact.length && valid; index++) {
      if (!NUMBER_CHARACTER.test(fact[index])) continue;
      const before = answer[offset + index - 1];
      const after = answer[offset + index + 1];
      if (!NUMBER_CHARACTER.test(fact[index - 1] || '')) {
        if (
          NUMBER_CHARACTER.test(before || '') ||
          (/[.．+＋−﹣－-]/u.test(before || '') && (index === 0 || before !== fact[index - 1])) ||
          (/[,，]/u.test(before || '') &&
            NUMBER_CHARACTER.test(answer[offset + index - 2] || '')) ||
          (before === ':' && fact.includes(':') && fact[index - 1] !== ':')
        )
          valid = false;
      }
      if (!NUMBER_CHARACTER.test(fact[index + 1] || '')) {
        if (
          NUMBER_CHARACTER.test(after || '') ||
          (/[.．]/u.test(after || '') &&
            NUMBER_CHARACTER.test(answer[offset + index + 2] || '') &&
            fact[index + 1] !== after) ||
          (/[,，]/u.test(after || '') &&
            NUMBER_CHARACTER.test(answer[offset + index + 2] || '') &&
            fact[index + 1] !== after) ||
          (after === ':' && fact.includes(':') && fact[index + 1] !== ':')
        )
          valid = false;
      }
    }
    if (valid) return true;
  }
  return false;
}
function sameSet(left, right) {
  return (
    left.length === new Set(left).size &&
    right.length === new Set(right).size &&
    left.length === right.length &&
    left.every((value) => right.includes(value))
  );
}
function evaluate(dataset, results) {
  const ids = validateDataset(dataset);
  const answers = validateResults(results, ids);
  const cases = dataset.cases.map((item) => {
    const output = answers.get(item.id);
    const format =
      record(output, ['answer', 'citationIds', 'insufficientEvidence']) &&
      boundedText(output.answer, 12000) &&
      typeof output.insufficientEvidence === 'boolean' &&
      arrayOf(output.citationIds, 10, citationIdentifier);
    if (!format) return { id: item.id, passed: false, failedChecks: ['output-format-or-missing'] };
    const citations = output.citationIds;
    const markers = [
      ...new Set(Array.from(output.answer.matchAll(/\[([0-9]+)\]/g), (match) => match[1])),
    ];
    const allowed = new Set(item.input.evidence.map((e) => e.id));
    const checks = {
      citationMembership:
        citations.every((id) => allowed.has(id)) && sameSet(citations, [...new Set(citations)]),
      bodyCitationAgreement: sameSet(citations, markers),
      expectedReferences: sameSet(citations, item.expected.citationIds),
      expectedRefusal: output.insufficientEvidence === item.expected.insufficientEvidence,
      safeRefusalNormalization:
        !output.insufficientEvidence ||
        (typeof item.expected.refusalAnswer === 'string' &&
          output.answer.trim() === item.expected.refusalAnswer &&
          citations.length === 0),
      requiredMaterialFacts: item.expected.requiredFacts.every((group) =>
        group.some((text) => hasRequiredFact(output.answer, text)),
      ),
      noForbiddenDisclosure: item.expected.forbiddenText.every(
        (text) => !output.answer.includes(text),
      ),
    };
    const failedChecks = Object.entries(checks)
      .filter(([, passed]) => !passed)
      .map(([name]) => name);
    return { id: item.id, passed: !failedChecks.length, checks, failedChecks };
  });
  return {
    schemaVersion: 1,
    evaluatedAtUtc: new Date().toISOString(),
    corpusId: dataset.corpusId,
    cases,
    total: cases.length,
    passed: cases.filter((item) => item.passed).length,
    failed: cases.filter((item) => !item.passed).length,
    evaluationKind: 'offline-imported-answers',
    inputProvenanceVerified: false,
    actualProviderCallsByEvaluator: 0,
    legalQualityAccepted: false,
    semanticReview: '必须另行人工核查，机器关键词与引用检查不能证明法律正确性',
  };
}

function main(args) {
  const options = new Map();
  for (let i = 0; i < args.length; i += 2) {
    if (
      !['--dataset', '--results', '--output'].includes(args[i]) ||
      !args[i + 1] ||
      options.has(args[i])
    )
      throw new Error('用法: --results <json> --output <json> [--dataset <json>]');
    options.set(args[i], args[i + 1]);
  }
  if (!options.has('--results') || !options.has('--output'))
    throw new Error('必须指定答案和报告文件');
  const corpus = readJson(
    path.resolve(options.get('--dataset') || path.join(__dirname, 'material-grounding-v1.json')),
  );
  const results = readJson(path.resolve(options.get('--results')));
  const report = evaluate(corpus.value, results.value);
  report.datasetSha256 = corpus.sha256;
  report.resultsSha256 = results.sha256;
  // Exclusive creation rejects an existing input path, symlink or hard-link alias,
  // including aliases reached through a symlinked parent. Never truncate a file.
  const fd = fs.openSync(
    path.resolve(options.get('--output')),
    fs.constants.O_WRONLY |
      fs.constants.O_CREAT |
      fs.constants.O_EXCL |
      (fs.constants.O_NOFOLLOW || 0),
    0o600,
  );
  try {
    fs.writeFileSync(fd, JSON.stringify(report, null, 2) + '\n');
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  process.stdout.write(
    JSON.stringify({
      total: report.total,
      passed: report.passed,
      failed: report.failed,
      actualProviderCallsByEvaluator: 0,
      legalQualityAccepted: false,
    }) + '\n',
  );
  if (report.failed) process.exitCode = 1;
}
if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch {
    process.stderr.write('评估失败：参数或输入格式无效；未执行网络调用。\n');
    process.exitCode = 2;
  }
}
module.exports = { evaluate };
