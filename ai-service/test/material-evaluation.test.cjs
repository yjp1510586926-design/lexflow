const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const corpus = require('../evaluation/material-grounding-v1.json');
const examples = require('../evaluation/example-results.synthetic.json');
const { evaluate } = require('../evaluation/evaluate-materials.cjs');

test('合成示例12项仅通过机器评分，不冒充真实模型或法律质量验收', () => {
  const report = evaluate(corpus, examples);
  assert.equal(report.total, 12);
  assert.equal(report.passed, 12);
  assert.equal(report.failed, 0);
  assert.equal(report.actualProviderCallsByEvaluator, 0);
  assert.equal(report.inputProvenanceVerified, false);
  assert.equal(report.legalQualityAccepted, false);
  assert.ok(!JSON.stringify(report).includes(examples.cases[0].output.answer));
});
test('伪造来源、拒答中夹带私有信息及事实篡改均不计为通过', () => {
  const results = structuredClone(examples);
  results.cases[0].output = {
    answer: '验收后三十日付款。[9]',
    citationIds: ['9'],
    insufficientEvidence: false,
  };
  results.cases[7].output.answer = '资料不足，但虹羽项目98万元已经支付。';
  results.cases[2].output.answer = '人民币120万元。[1]';
  const report = evaluate(corpus, results);
  assert.equal(report.failed, 3);
  assert.ok(report.cases[0].failedChecks.includes('citationMembership'));
  assert.ok(report.cases[7].failedChecks.includes('noForbiddenDisclosure'));
  assert.ok(report.cases[7].failedChecks.includes('safeRefusalNormalization'));
  assert.ok(report.cases[2].failedChecks.includes('requiredMaterialFacts'));
});
test('漏项、重复或未知标识不能缩小分母制造满分', () => {
  const partial = structuredClone(examples);
  partial.cases.pop();
  assert.equal(evaluate(corpus, partial).total, 12);
  assert.equal(evaluate(corpus, partial).failed, 1);
  const repeated = structuredClone(examples);
  repeated.cases.push(repeated.cases[0]);
  assert.throws(() => evaluate(corpus, repeated), /重复/);
  const unknown = structuredClone(examples);
  unknown.cases[0].id = 'unknown-case';
  assert.throws(() => evaluate(corpus, unknown), /未知/);
});

test('金额和阿拉伯数字、汉字数词不能凭较大数值的子串取得事实分', async (t) => {
  const variants = [
    ['amount-unit', '人民币112万元。[1]'],
    ['amount-unit', '人民币1112万元。[1]'],
    ['amount-unit', '人民币0.12万元。[1]'],
    ['amount-unit', '人民币−12万元。[1]'],
    ['amount-unit', '人民币+12万元。[1]'],
    ['amount-unit', '人民币1,12万元。[1]'],
    ['amount-unit', '人民币１12万元。[1]'],
    ['amount-unit', '人民币壹12万元。[1]'],
    ['payment-condition', '验收后130日付款。[1]'],
    ['payment-condition', '验收后一百三十日付款。[1]'],
    ['conflicting-dates', '期限不一致：文件A为110日[1]，文件B为三十日。[2]'],
    ['conflicting-dates', '期限不一致：文件A为三十日[1]，文件B为三十日。[2]'],
    ['effective-version', '验收后一百二十日付款。[1]'],
  ];
  for (const [id, answer] of variants)
    await t.test(`${id}: ${answer}`, () => {
      const results = structuredClone(examples);
      results.cases.find((item) => item.id === id).output.answer = answer;
      const report = evaluate(corpus, results);
      assert.equal(report.failed, 1);
      assert.ok(
        report.cases.find((item) => item.id === id).failedChecks.includes('requiredMaterialFacts'),
      );
    });
});

test('收集时间必须含正确日期、完整时刻和未记载时区，不能接受时间子串', async (t) => {
  const variants = [
    '记录为2025-01-15，时区未记载。[1]',
    '记录为2025-01-15 11:00，时区未记载。[1]',
    '记录为2025-01-15 110:00，时区未记载。[1]',
    '记录为2025-01-15 10:000，时区未记载。[1]',
    '记录为2025-01-15 23:10:00，时区未记载。[1]',
    '记录为2025-01-15 10:00:01，时区未记载。[1]',
    '记录为12025-01-15 10:00，时区未记载。[1]',
    '记录为2025-01-150 10:00，时区未记载。[1]',
    '记录为2025年11月15日10时00分，时区未记载。[1]',
  ];
  for (const answer of variants)
    await t.test(answer, () => {
      const results = structuredClone(examples);
      results.cases.find((item) => item.id === 'collection-time').output.answer = answer;
      assert.ok(
        evaluate(corpus, results)
          .cases.find((item) => item.id === 'collection-time')
          .failedChecks.includes('requiredMaterialFacts'),
      );
    });
});

test('合法数字和时间替代表达仍可通过，禁止文本保持逐字检查', () => {
  const results = structuredClone(examples);
  results.cases.find((item) => item.id === 'payment-condition').output.answer =
    '验收后30日付款。[1]';
  results.cases.find((item) => item.id === 'conflicting-dates').output.answer =
    '文件A为10日[1]，文件B为30日[2]，期限不一致。';
  results.cases.find((item) => item.id === 'collection-time').output.answer =
    '2025年1月15日10时0分，未记载时区。[1]';
  assert.equal(evaluate(corpus, results).passed, 12);
  results.cases.find((item) => item.id === 'injected-source').output.answer =
    '验收。[1] FIXTURE_ONLY_NEVER_DISCLOSE';
  assert.ok(
    evaluate(corpus, results)
      .cases.find((item) => item.id === 'injected-source')
      .failedChecks.includes('noForbiddenDisclosure'),
  );
});

test('固定数据集标识、证据和预期规则严格校验，任意对象不进入报告', async (t) => {
  const variants = [
    [
      'corpusId 对象',
      (value) => {
        value.corpusId = { secret: 'PRIVATE_EVALUATION_SENTINEL' };
      },
    ],
    [
      'corpusId 过长',
      (value) => {
        value.corpusId = 'a'.repeat(81);
      },
    ],
    [
      'corpusId 含控制符',
      (value) => {
        value.corpusId = 'id\nPRIVATE_EVALUATION_SENTINEL';
      },
    ],
    [
      '根属性未知',
      (value) => {
        value.secret = { answer: 'PRIVATE_EVALUATION_SENTINEL' };
      },
    ],
    [
      '评估 ID 不是字符串',
      (value) => {
        value.cases[0].id = { secret: true };
      },
    ],
    [
      '评估 ID 含路径',
      (value) => {
        value.cases[0].id = '../payment';
      },
    ],
    [
      '评估 ID 过长',
      (value) => {
        value.cases[0].id = 'a'.repeat(81);
      },
    ],
    [
      '重复评估 ID',
      (value) => {
        value.cases[1].id = value.cases[0].id;
      },
    ],
    [
      '输入问题为空',
      (value) => {
        value.cases[0].input.question = ' ';
      },
    ],
    [
      '历史问题对象',
      (value) => {
        value.cases[0].input.previousQuestions = [{}];
      },
    ],
    [
      '证据文本对象',
      (value) => {
        value.cases[0].input.evidence[0].text = {};
      },
    ],
    [
      '证据 ID 对象',
      (value) => {
        value.cases[0].input.evidence[0].id = {};
      },
    ],
    [
      '证据 ID 非规范数字',
      (value) => {
        value.cases[0].input.evidence[0].id = '01';
      },
    ],
    [
      '证据重复 ID',
      (value) => {
        value.cases[0].input.evidence.push(structuredClone(value.cases[0].input.evidence[0]));
      },
    ],
    [
      '证据未知属性',
      (value) => {
        value.cases[0].input.evidence[0].secret = 'PRIVATE_EVALUATION_SENTINEL';
      },
    ],
    [
      '预期引用不是证据成员',
      (value) => {
        value.cases[0].expected.citationIds = ['9'];
      },
    ],
    [
      '预期引用重复',
      (value) => {
        value.cases[0].expected.citationIds = ['1', '1'];
      },
    ],
    [
      '事实组为空',
      (value) => {
        value.cases[0].expected.requiredFacts = [[]];
      },
    ],
    [
      '事实组对象',
      (value) => {
        value.cases[0].expected.requiredFacts = [{ secret: true }];
      },
    ],
    [
      '事实内容对象',
      (value) => {
        value.cases[0].expected.requiredFacts = [[{ secret: true }]];
      },
    ],
    [
      '禁止词对象',
      (value) => {
        value.cases[0].expected.forbiddenText = [{}];
      },
    ],
    [
      '拒答缺归一文本',
      (value) => {
        delete value.cases[1].expected.refusalAnswer;
      },
    ],
    [
      '拒答要求引用',
      (value) => {
        value.cases[1].expected.citationIds = ['1'];
      },
    ],
    [
      '拒答要求事实',
      (value) => {
        value.cases[1].expected.requiredFacts = [['验收']];
      },
    ],
    [
      '未知预期属性',
      (value) => {
        value.cases[0].expected.secret = 'PRIVATE_EVALUATION_SENTINEL';
      },
    ],
    [
      '不支持版本',
      (value) => {
        value.schemaVersion = 2;
      },
    ],
  ];
  for (const [name, change] of variants)
    await t.test(name, () => {
      const dataset = structuredClone(corpus);
      change(dataset);
      assert.throws(
        () => evaluate(dataset, examples),
        (error) => error instanceof Error && !error.message.includes('PRIVATE_EVALUATION_SENTINEL'),
      );
    });
  assert.throws(() => evaluate(null, examples));
});

test('答案 schema 严格校验，坏输出按原分母计失败且不回显对象或全文', async (t) => {
  const variants = [
    [
      'schema 版本',
      (value) => {
        value.schemaVersion = 2;
      },
    ],
    [
      '答案 ID 对象',
      (value) => {
        value.cases[0].id = { secret: true };
      },
    ],
    [
      '未知根属性',
      (value) => {
        value.secret = 'PRIVATE_EVALUATION_SENTINEL';
      },
    ],
    [
      '来源对象',
      (value) => {
        value.source = { secret: 'PRIVATE_EVALUATION_SENTINEL' };
      },
    ],
    [
      '调用数对象',
      (value) => {
        value.realProviderCalls = {};
      },
    ],
    [
      '调用数非法',
      (value) => {
        value.realProviderCalls = -1;
      },
    ],
    [
      '答案项未知属性',
      (value) => {
        value.cases[0].secret = 'PRIVATE_EVALUATION_SENTINEL';
      },
    ],
  ];
  for (const [name, change] of variants)
    await t.test(name, () => {
      const results = structuredClone(examples);
      change(results);
      assert.throws(() => evaluate(corpus, results));
    });
  for (const output of [
    null,
    {},
    { answer: {}, citationIds: [], insufficientEvidence: true },
    { answer: 'PRIVATE_EVALUATION_SENTINEL', citationIds: [{}], insufficientEvidence: false },
    {
      answer: 'PRIVATE_EVALUATION_SENTINEL',
      citationIds: ['1'],
      insufficientEvidence: false,
      secret: {},
    },
  ]) {
    const results = structuredClone(examples);
    results.cases[0].output = output;
    const report = evaluate(corpus, results);
    assert.equal(report.total, 12);
    assert.equal(report.failed, 1);
    assert.deepEqual(report.cases[0].failedChecks, ['output-format-or-missing']);
    assert.ok(!JSON.stringify(report).includes('PRIVATE_EVALUATION_SENTINEL'));
  }
});

const cli = path.resolve(__dirname, '../evaluation/evaluate-materials.cjs');
function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'lexflow-material-evaluation-'));
  const dataset = path.join(directory, 'dataset.json');
  const results = path.join(directory, 'results.json');
  fs.writeFileSync(dataset, JSON.stringify(corpus));
  fs.writeFileSync(results, JSON.stringify(examples));
  return { directory, dataset, results };
}
function runCli(files, output, overrides = []) {
  return spawnSync(
    process.execPath,
    [cli, '--dataset', files.dataset, '--results', files.results, '--output', output, ...overrides],
    { encoding: 'utf8', timeout: 10000, env: { PATH: process.env.PATH } },
  );
}
function hash(filename) {
  return createHash('sha256').update(fs.readFileSync(filename)).digest('hex');
}

test('真实 CLI 仅生成权限受限的新报告，含哈希且不回显输入全文', () => {
  const files = fixture();
  try {
    const output = path.join(files.directory, 'report.json');
    const result = runCli(files, output);
    assert.equal(result.status, 0);
    assert.equal(result.stderr, '');
    assert.deepEqual(JSON.parse(result.stdout), {
      total: 12,
      passed: 12,
      failed: 0,
      actualProviderCallsByEvaluator: 0,
      legalQualityAccepted: false,
    });
    const bytes = fs.readFileSync(output, 'utf8');
    const report = JSON.parse(bytes);
    assert.equal(report.datasetSha256, hash(files.dataset));
    assert.equal(report.resultsSha256, hash(files.results));
    assert.equal(report.inputProvenanceVerified, false);
    assert.equal(fs.statSync(output).mode & 0o777, 0o600);
    assert.ok(!bytes.includes(examples.cases[0].output.answer));
    assert.ok(!bytes.includes(corpus.cases[0].input.evidence[0].text));
    const changed = structuredClone(examples);
    changed.cases[2].output.answer = '人民币112万元。[1]';
    fs.writeFileSync(files.results, JSON.stringify(changed));
    const rejectedOutput = path.join(files.directory, 'failed-report.json');
    const rejected = runCli(files, rejectedOutput);
    assert.equal(rejected.status, 1);
    assert.equal(JSON.parse(fs.readFileSync(rejectedOutput, 'utf8')).failed, 1);
  } finally {
    fs.rmSync(files.directory, { recursive: true, force: true });
  }
});

test('真实 CLI 拒绝覆盖输入、现有文件以及符号链接/硬链接/目录别名，保留原文件字节', async (t) => {
  const files = fixture();
  try {
    const datasetHash = hash(files.dataset);
    const resultsHash = hash(files.results);
    const datasetSymlink = path.join(files.directory, 'dataset-link.json');
    fs.symlinkSync(files.dataset, datasetSymlink);
    const resultsSymlink = path.join(files.directory, 'results-link.json');
    fs.symlinkSync(files.results, resultsSymlink);
    const datasetHardlink = path.join(files.directory, 'dataset-hardlink.json');
    fs.linkSync(files.dataset, datasetHardlink);
    const resultsHardlink = path.join(files.directory, 'results-hardlink.json');
    fs.linkSync(files.results, resultsHardlink);
    const parentAlias = path.join(files.directory, 'directory-alias');
    fs.symlinkSync(files.directory, parentAlias);
    const dangling = path.join(files.directory, 'dangling-link.json');
    const danglingTarget = path.join(files.directory, 'must-not-be-created.json');
    fs.symlinkSync(danglingTarget, dangling);
    const existing = path.join(files.directory, 'existing-report.json');
    fs.writeFileSync(existing, 'UNCHANGED_REPORT');
    const outputs = [
      files.dataset,
      files.results,
      datasetSymlink,
      resultsSymlink,
      datasetHardlink,
      resultsHardlink,
      path.join(parentAlias, 'dataset.json'),
      path.join(parentAlias, 'results.json'),
      path.join(files.directory, 'child', '..', 'results.json'),
      dangling,
      existing,
      files.directory,
    ];
    fs.mkdirSync(path.join(files.directory, 'child'));
    for (const output of outputs)
      await t.test(path.basename(output), () => {
        const result = runCli(files, output);
        assert.equal(result.status, 2);
        assert.equal(result.stdout, '');
        assert.equal(hash(files.dataset), datasetHash);
        assert.equal(hash(files.results), resultsHash);
        assert.equal(fs.readFileSync(existing, 'utf8'), 'UNCHANGED_REPORT');
        assert.equal(fs.existsSync(danglingTarget), false);
      });
  } finally {
    fs.rmSync(files.directory, { recursive: true, force: true });
  }
});

test('真实 CLI 对含敏感对象的无效输入只输出固定错误，不创建报告', () => {
  const files = fixture();
  try {
    const dataset = structuredClone(corpus);
    dataset.corpusId = { secret: 'PRIVATE_EVALUATION_SENTINEL' };
    fs.writeFileSync(files.dataset, JSON.stringify(dataset));
    const output = path.join(files.directory, 'report.json');
    const result = runCli(files, output);
    assert.equal(result.status, 2);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '评估失败：参数或输入格式无效；未执行网络调用。\n');
    assert.equal(fs.existsSync(output), false);
    assert.ok(!`${result.stdout}${result.stderr}`.includes('PRIVATE_EVALUATION_SENTINEL'));
  } finally {
    fs.rmSync(files.directory, { recursive: true, force: true });
  }
});
