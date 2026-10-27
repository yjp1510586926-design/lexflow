const { test } = require('node:test'),
  assert = require('node:assert/strict');
const fs = require('node:fs'),
  os = require('node:os'),
  path = require('node:path');
const { spawnSync } = require('node:child_process');
const common = require('../evaluation/real-evaluation-common.cjs');
const { createGate } = require('../evaluation/provider-gate.cjs');
const { remapAnswer, execute } = require('../evaluation/run-real-materials.cjs');
const datasetBytes = fs.readFileSync(
  path.join(__dirname, '../evaluation/material-grounding-v1.json'),
);
function fixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lexflow-eval-gate-'));
  const write = (name, value) => {
    const file = path.join(dir, name);
    fs.writeFileSync(file, value, { mode: 0o600 });
    return file;
  };
  const config = JSON.parse(
    fs.readFileSync(path.join(__dirname, '../evaluation/real-evaluation.example.json')),
  );
  Object.assign(config, {
    authorized: true,
    runId: 'synthetic-run',
    datasetSha256: common.sha(datasetBytes),
    javaUrl: 'http://127.0.0.1:19888',
    credentialsFile: write('credentials.json', '{}'),
    authorizationRecordFile: path.join(dir, 'approval.json'),
  });
  config.ownedRuntime = {
    database: 'lexflow_eval_' + 'a'.repeat(32),
    aiDatabase: 'lexflow_ai_' + 'b'.repeat(32),
    bucket: 'synthetic-run-bucket',
    vhost: 'synthetic-run-vhost',
    redisNamespace: 'synthetic-run-redis',
  };
  config.provider = {
    name: 'fixture-no-provider',
    baseUrl: 'https://fixture.invalid/v1',
    keyFile: write('key', 'synthetic-provider-key-never-paid'),
  };
  for (const purpose of ['EMBEDDING', 'LLM'])
    Object.assign(config.models[purpose], {
      model: purpose === 'LLM' ? 'synthetic-chat' : 'synthetic-embedding',
      responseModels: [purpose === 'LLM' ? 'synthetic-chat' : 'synthetic-embedding'],
      encoding: 'o200k_base',
      maxInputTokens: 1000,
      maxCalls: 10,
      inputMicrosPerMillion: 1000000,
      messageOverheadTokens: purpose === 'LLM' ? 64 : 0,
    });
  Object.assign(config.models.LLM, { maxOutputTokens: 64, outputMicrosPerMillion: 2000000 });
  config.limits = {
    maxCalls: 20,
    budgetMicrosUSD: 10000,
    maxRequestBytes: 65536,
    requestTimeoutMs: 1000,
  };
  config.gateway = {
    url: 'https://127.0.0.1:19889/v1',
    tlsKeyFile: path.join(dir, 'unused-key'),
    tlsCertFile: path.join(dir, 'unused-cert'),
    tokenFile: write('token', 'synthetic-gateway-token-never-paid'),
    ledgerDirectory: path.join(dir, 'ledger'),
  };
  config.pricing = {
    sourceUrl: 'https://fixture.invalid/reviewed-rates',
    reviewedAt: '2026-10-09',
  };
  const approve = () =>
    write(
      'approval.json',
      JSON.stringify({
        runId: config.runId,
        approvedBy: 'Synthetic reviewer',
        approvedAt: '2026-10-09T00:00:00Z',
        recordReference: 'unit fixture; no human approval for a real provider',
        configurationSha256: common.configHash(config),
      }),
    );
  approve();
  return {
    dir,
    config,
    write,
    approve,
    clean: () => fs.rmSync(dir, { recursive: true, force: true }),
  };
}
const chat = () => ({
  model: 'synthetic-chat',
  messages: [{ role: 'user', content: '仅测试' }],
  max_tokens: 10,
  response_format: { type: 'json_object' },
});
test('默认CLI无网络dry-run；示例配置不能启动execute；既有输出不覆盖', () => {
  const f = fixture();
  try {
    const blocker = f.write(
      'block-network.cjs',
      "global.fetch=()=>{throw Error('network forbidden')};require('node:https').request=()=>{throw Error('network forbidden')};require('node:http').request=()=>{throw Error('network forbidden')};\n",
    );
    const cli = path.join(__dirname, '../evaluation/run-real-materials.cjs'),
      output = path.join(f.dir, 'dry.json'),
      example = path.join(__dirname, '../evaluation/real-evaluation.example.json');
    const result = spawnSync(
      process.execPath,
      ['--require', blocker, cli, '--config', example, '--output', output],
      { encoding: 'utf8' },
    );
    assert.equal(result.status, 0);
    const report = JSON.parse(fs.readFileSync(output));
    assert.equal(report.networkRequests, 0);
    assert.equal(report.actualProviderCalls, 0);
    assert.equal(report.questions, 12);
    assert.equal(fs.statSync(output).mode & 0o777, 0o600);
    assert.equal(spawnSync(process.execPath, [cli, '--output', output]).status, 2);
    assert.equal(
      spawnSync(process.execPath, [
        '--require',
        blocker,
        cli,
        '--execute',
        '--config',
        example,
        '--output',
        path.join(f.dir, 'actual'),
      ]).status,
      2,
    );
    assert.ok(!fs.existsSync(path.join(f.dir, 'actual')));
    assert.equal(JSON.parse(fs.readFileSync(output)).evaluationKind, 'dry-run-no-network');
  } finally {
    f.clean();
  }
});
test('授权必须绑定dataset和完整配置；私密文件不得宽权限或symlink；人工空表不通过', () => {
  const f = fixture();
  try {
    assert.equal(common.validate(f.config, common.sha(datasetBytes)), f.config);
    for (const change of [
      { authorized: false },
      { datasetSha256: '0'.repeat(64) },
      { runId: null },
      { javaUrl: 'http://external.invalid' },
      { unknown: true },
    ])
      assert.throws(() => common.validate({ ...f.config, ...change }, common.sha(datasetBytes)));
    const changed = structuredClone(f.config);
    changed.limits.maxCalls++;
    assert.throws(() => common.validate(changed, common.sha(datasetBytes)));
    fs.chmodSync(f.config.provider.keyFile, 0o644);
    assert.throws(() => common.secret(f.config.provider.keyFile));
    fs.chmodSync(f.config.provider.keyFile, 0o600);
    const link = path.join(f.dir, 'link');
    fs.symlinkSync(f.config.provider.keyFile, link);
    assert.throws(() => common.secret(link));
    assert.equal(
      common.humanGate(
        {
          schemaVersion: 1,
          cases: [{ id: 'a', accepted: true, reviewer: '', reviewedAt: '', reason: '' }],
        },
        ['a'],
      ),
      false,
    );
    assert.equal(
      common.humanGate(
        {
          schemaVersion: 1,
          cases: [
            {
              id: 'a',
              accepted: true,
              reviewer: 'Alice',
              reviewedAt: '2026-10-09T00:00:00Z',
              reason: '已逐项核对原文、引用、否定和角色归属。',
            },
          ],
        },
        ['a'],
      ),
      true,
    );
  } finally {
    f.clean();
  }
});
test('记录器固定模型/路径字段/输入输出和请求数，INTENT先落盘；usage未知保留金额且不自动重发', async () => {
  const f = fixture();
  let requests = 0;
  let gate;
  try {
    gate = createGate(f.config, {
      fetch: async () => {
        requests++;
        const lines = fs
          .readFileSync(path.join(f.config.gateway.ledgerDirectory, 'provider-calls.jsonl'), 'utf8')
          .trim()
          .split('\n')
          .map(JSON.parse);
        assert.equal(lines.at(-1).kind, 'INTENT');
        return new Response(
          JSON.stringify({
            model: 'synthetic-chat',
            choices: [],
            usage: { prompt_tokens: 4, completion_tokens: 2, total_tokens: 6 },
          }),
          { status: 200, headers: { 'x-request-id': 'fixture-request' } },
        );
      },
    });
    gate.state.phase = 'answer:fixture';
    for (const change of [
      { model: 'unapproved' },
      { stream: true },
      { n: 2 },
      { max_tokens: 65 },
      { web_search_options: {} },
      { tools: [] },
      { messages: [{ role: 'user', content: [{ type: 'image_url' }] }] },
    ])
      await assert.rejects(
        gate.forward('LLM', Buffer.from(JSON.stringify({ ...chat(), ...change }))),
      );
    assert.equal(requests, 0);
    const response = await gate.forward('LLM', Buffer.from(JSON.stringify(chat())));
    assert.equal(response.status, 200);
    assert.equal(requests, 1);
    assert.equal(gate.state.estimatedMicros, 8);
    assert.equal(gate.state.heldMicros, 8);
    const ledger = fs.readFileSync(
      path.join(f.config.gateway.ledgerDirectory, 'provider-calls.jsonl'),
      'utf8',
    );
    assert.ok(!ledger.includes('synthetic-provider-key'));
    assert.ok(!ledger.includes('仅测试'));
    assert.equal(
      fs.statSync(path.join(f.config.gateway.ledgerDirectory, 'provider-calls.jsonl')).mode & 0o777,
      0o600,
    );
    gate.close();
    gate = null;
    assert.throws(() => createGate(f.config), /EEXIST/);
    f.config.gateway.ledgerDirectory = path.join(f.dir, 'unknown-ledger');
    gate = createGate(f.config, {
      fetch: async () => {
        requests++;
        return new Response(JSON.stringify({ model: 'synthetic-chat', choices: [] }), {
          status: 200,
        });
      },
    });
    gate.state.phase = 'answer:fixture';
    await gate.forward('LLM', Buffer.from(JSON.stringify(chat())));
    assert.equal(gate.state.halted, true);
    assert.equal(gate.state.usageUnknown, 1);
    assert.equal(gate.state.heldMicros, 1128);
    await assert.rejects(gate.forward('LLM', Buffer.from(JSON.stringify(chat()))));
    assert.equal(requests, 2);
  } finally {
    gate?.close();
    f.clean();
  }
});
test('并发请求先预留金额；超限不会向供应商转发；动态引用仅按真实source映射', async () => {
  const f = fixture();
  let gate;
  try {
    f.config.limits.budgetMicrosUSD = 1128;
    let resolve,
      requests = 0;
    const pending = new Promise((r) => (resolve = r));
    gate = createGate(f.config, {
      fetch: async () => {
        requests++;
        await pending;
        return new Response(
          JSON.stringify({
            model: 'synthetic-chat',
            usage: { prompt_tokens: 4, completion_tokens: 2, total_tokens: 6 },
          }),
        );
      },
    });
    gate.state.phase = 'answer:fixture';
    const one = gate.forward('LLM', Buffer.from(JSON.stringify(chat())));
    await assert.rejects(gate.forward('LLM', Buffer.from(JSON.stringify(chat()))));
    assert.equal(requests, 1);
    resolve();
    await one;
    const gold = new Map([['61', { goldId: '2', fileVersionId: '51', documentId: '41' }]]);
    const answer = {
      status: 'SUCCEEDED',
      sourcesUnavailable: false,
      answer: '材料事实[4]',
      insufficientEvidence: false,
      citations: [{ id: '4', chunkId: '61', fileVersionId: '51', documentId: '41' }],
    };
    assert.deepEqual(remapAnswer({}, answer, gold), {
      answer: '材料事实[2]',
      citationIds: ['2'],
      insufficientEvidence: false,
    });
    assert.deepEqual(
      remapAnswer(
        {},
        { ...answer, citations: [{ ...answer.citations[0], fileVersionId: '52' }] },
        gold,
      ).citationIds,
      ['9999'],
    );
    assert.equal(remapAnswer({}, { ...answer, sourcesUnavailable: true }, gold), null);
  } finally {
    gate?.close();
    f.clean();
  }
});
test('非空验收租户在模型配置前拒绝，清理不会删除既有配置；未执行任务保留完整分母', async () => {
  const f = fixture(),
    originalFetch = global.fetch,
    calls = [];
  try {
    f.write(
      'credentials.json',
      JSON.stringify({
        owner: { username: 'owner', password: 'fixture-only-password', tenantId: '190001' },
        otherTenant: { username: 'other', password: 'fixture-only-password', tenantId: '190002' },
      }),
    );
    global.fetch = async (input, init = {}) => {
      const route = new URL(input).pathname,
        method = init.method ?? 'GET';
      calls.push({ route, method });
      if (route.startsWith('/__lexflow_eval/'))
        return Response.json({
          runId: f.config.runId,
          configurationSha256: common.configHash(f.config),
          calls: 0,
          halted: false,
        });
      let data;
      if (route === '/api/v1/auth/csrf')
        data = { headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' };
      else if (route === '/api/v1/auth/login') {
        const body = JSON.parse(init.body);
        data = { id: '1', tenantId: body.tenantId };
      } else if (route === '/api/v1/cases') data = { total: 0 };
      else if (route === '/api/v1/tenant-knowledge-bases') data = { items: [] };
      else if (route === '/api/v1/settings/ai')
        data = {
          storageReady: true,
          items: [{ purpose: 'LLM', model: 'preexisting-user-config' }],
        };
      else throw Error('unexpected fixture route');
      return Response.json({ code: 200, data });
    };
    const report = await execute(f.config, JSON.parse(datasetBytes), path.join(f.dir, 'report'));
    assert.equal(report.fatal, true);
    assert.equal(report.completedQuestions, 0);
    assert.ok(report.expectedTaskCount > 0);
    assert.equal(report.taskSuccessRate, 0);
    assert.ok(
      !calls.some(
        (c) =>
          c.route.startsWith('/api/v1/settings/ai/') || c.method === 'DELETE' || c.method === 'PUT',
      ),
    );
    assert.equal(report.humanSemanticReviewPassed, false);
    assert.equal(report.legalQualityAccepted, false);
    assert.equal(
      common.humanGate(
        common.readJson(path.join(f.dir, 'report/human-review.private.json'), true),
        JSON.parse(datasetBytes).cases.map((c) => c.id),
      ),
      false,
    );
  } finally {
    global.fetch = originalFetch;
    f.clean();
  }
});
test('十二项执行器通过模拟Java协议验证上传/任务/检索/会话/权限/精确清理；模拟答案不作为真实质量', async () => {
  const f = fixture(),
    originalFetch = global.fetch;
  let sequence = 100,
    activeUser = 0;
  const next = () => String(++sequence);
  const accounts = new Map(),
    files = new Map(),
    versions = new Map(),
    bases = new Map(),
    tasks = new Map(),
    documents = new Map(),
    sessions = new Map(),
    routes = [];
  const dataset = JSON.parse(datasetBytes);
  try {
    f.write(
      'credentials.json',
      JSON.stringify({
        owner: { username: 'owner', password: 'fixture-only-password', tenantId: '190001' },
        otherTenant: { username: 'other', password: 'fixture-only-password', tenantId: '190002' },
      }),
    );
    accounts.set('owner', { id: '1', tenantId: '190001' });
    accounts.set('other', { id: '2', tenantId: '190002' });
    const reply = (data, status = 200, headers = {}) =>
      Response.json(
        { code: status < 400 ? 200 : status, data, traceId: 'mock-only-trace' },
        { status, headers },
      );
    const owned = (base) => activeUser === 1 && bases.has(base);
    global.fetch = async (input, init = {}) => {
      const route = new URL(input).pathname,
        method = init.method ?? 'GET';
      routes.push({ route, method });
      if (route.startsWith('/__lexflow_eval/'))
        return Response.json({
          runId: f.config.runId,
          configurationSha256: common.configHash(f.config),
          calls: 0,
          halted: false,
        });
      activeUser = Number(/LEXSESSION=([0-9]+)/.exec(init.headers?.cookie ?? '')?.[1] ?? 0);
      const body =
        init.body instanceof FormData
          ? init.body
          : init.body === undefined
            ? {}
            : JSON.parse(init.body);
      if (route === '/api/v1/auth/csrf')
        return reply({ headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' });
      if (route === '/api/v1/auth/login') {
        const account = accounts.get(body.username);
        assert.ok(account);
        return reply(account, 200, { 'set-cookie': 'LEXSESSION=' + account.id + '; Path=/' });
      }
      if (route === '/api/v1/cases' && method === 'GET') return reply({ total: 0 });
      if (route === '/api/v1/tenant-knowledge-bases' && method === 'GET')
        return reply({ items: [] });
      if (route === '/api/v1/settings/ai') return reply({ storageReady: true, items: [] });
      if (route.startsWith('/api/v1/settings/ai/')) return reply(null);
      if (route === '/api/v1/organization/users' && method === 'POST') {
        const account = { id: next(), tenantId: '190001' };
        accounts.set(body.username, account);
        return reply(account);
      }
      if (route.startsWith('/api/v1/organization/users/') && method === 'PATCH') return reply(null);
      if (route === '/api/v1/cases' && method === 'POST') return reply({ id: next() });
      if (
        ['/api/v1/knowledge-bases', '/api/v1/tenant-knowledge-bases'].includes(route) &&
        method === 'POST'
      ) {
        const id = next();
        bases.set(id, { caseId: body.caseId ?? null });
        return reply({ id });
      }
      let match;
      if (
        (match = /^\/api\/v1\/(?:cases|tenant-knowledge-bases)\/([0-9]+)\/files$/.exec(route)) &&
        method === 'POST'
      ) {
        const id = next(),
          versionId = next(),
          text = await body.get('file').text();
        const file = { id, versionId, text, deleted: false };
        files.set(id, file);
        versions.set(versionId, file);
        return reply({ id, versionId });
      }
      if (
        (match =
          /^\/api\/v1\/(?:files\/|tenant-knowledge-bases\/[0-9]+\/files\/)([0-9]+)(\/download|\/parse)?$/.exec(
            route,
          ))
      ) {
        const file = files.get(match[1]);
        assert.ok(file);
        if (match[2] === '/download')
          return file.deleted ? reply(null, 403) : new Response(file.text);
        if (match[2] === '/parse') {
          const id = next();
          tasks.set(id, { id, status: 'SUCCEEDED', attempt: 1, file });
          return reply({ id });
        }
        if (method === 'DELETE') {
          assert.equal(file.deleted, false);
          file.deleted = true;
          return reply(null);
        }
      }
      if (route === '/api/v1/tasks' && method === 'POST') {
        const id = next();
        tasks.set(id, {
          id,
          status: 'SUCCEEDED',
          attempt: 1,
          file: versions.get(body.resourceVersionId),
        });
        return reply({ id });
      }
      if ((match = /^\/api\/v1\/tasks\/([0-9]+)$/.exec(route))) return reply(tasks.get(match[1]));
      if (
        (match = /^\/api\/v1\/knowledge-bases\/([0-9]+)\/documents$/.exec(route)) &&
        method === 'POST'
      ) {
        const id = next(),
          chunkId = next();
        documents.set(id, { id, chunkId, base: match[1], file: tasks.get(body.parseTaskId).file });
        return reply({ id });
      }
      if (
        (match = /^\/api\/v1\/knowledge-bases\/[0-9]+\/documents\/([0-9]+)\/(chunks|index)$/.exec(
          route,
        ))
      ) {
        const document = documents.get(match[1]);
        assert.ok(document);
        if (match[2] === 'chunks')
          return reply([{ id: document.chunkId, text: document.file.text }]);
        const id = next();
        tasks.set(id, { id, status: 'SUCCEEDED', attempt: 1 });
        return reply({ taskId: id });
      }
      if (route === '/api/v1/ai/sessions') {
        const id = next();
        sessions.set(id, body.baseId);
        return reply({ id });
      }
      if ((match = /^\/api\/v1\/knowledge-bases\/([0-9]+)\/search$/.exec(route))) {
        if (!owned(match[1])) return reply(null, 403);
        return reply({
          citations: [...documents.values()]
            .filter((d) => d.base === match[1] && !d.file.deleted)
            .map((d) => ({
              chunkId: d.chunkId,
              documentId: d.id,
              fileVersionId: d.file.versionId,
            })),
        });
      }
      if ((match = /^\/api\/v1\/ai\/sessions\/([0-9]+)\/messages$/.exec(route))) {
        if (!owned(sessions.get(match[1]))) return reply(null, 403);
        return reply({
          id: next(),
          status: 'SUCCEEDED',
          sourcesUnavailable: false,
          answer: '模拟协议测试返回值：不构成真实模型质量证据。',
          citations: [],
          insufficientEvidence: true,
        });
      }
      throw Error('unexpected fixture route');
    };
    const report = await execute(f.config, dataset, path.join(f.dir, 'report'));
    assert.equal(report.fatal, false);
    assert.equal(report.completedQuestions, 12);
    assert.equal(report.expectedTaskCount, report.observedTaskCount);
    assert.equal(report.taskSuccessRate, 1);
    assert.equal(report.permissionIsolationPassed, true);
    assert.equal(report.providerEvidence.calls, 0);
    assert.ok(report.scores.failed > 0);
    assert.equal(report.legalQualityAccepted, false);
    assert.equal(report.isolatedRuntimeTeardownVerified, false);
    assert.ok([...files.values()].every((x) => x.deleted));
    assert.equal(
      report.cleanup.filter((c) => c.kind === 'model-configuration' && c.removed).length,
      2,
    );
    assert.equal(
      report.observations.filter(
        (o) => o.probe === 'independent authorized retrieval' && o.excludedChunkIds.length,
      ).length,
      2,
    );
    assert.ok(
      report.observations
        .filter((o) => o.probe === 'independent authorized retrieval')
        .every((o) => o.excludedSourcesAbsent),
    );
    assert.ok(
      routes.filter(
        (r) => r.method === 'POST' && /^\/api\/v1\/ai\/sessions\/[0-9]+\/messages$/.test(r.route),
      ).length >=
        12 * 3,
    );
  } finally {
    global.fetch = originalFetch;
    f.clean();
  }
});
