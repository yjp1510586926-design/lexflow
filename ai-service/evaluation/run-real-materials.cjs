#!/usr/bin/env node
'use strict';
const fs = require('node:fs'),
  path = require('node:path');
const { randomUUID, randomBytes } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const common = require('./real-evaluation-common.cjs'),
  { evaluate } = require('./evaluate-materials.cjs');
const TENANT_REFUSAL = '当前律所知识库资料不足以回答这个问题，请补充相关材料后再提问。';
function identifier(value) {
  if (
    typeof value !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  )
    throw new Error('服务器资源标识无效');
  return value;
}

class JavaApi {
  constructor(url) {
    this.url = url.replace(/\/$/, '');
    this.cookies = new Map();
    this.csrf = null;
  }
  async request(method, route, body, allowed = [200, 201, 202], binary = false) {
    if (!route.startsWith('/api/v1/') || route.includes('..')) throw new Error('API范围无效');
    const headers = { cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join(';') };
    if (method !== 'GET' && this.csrf) headers[this.csrf.headerName] = this.csrf.token;
    if (body !== undefined && !(body instanceof FormData))
      headers['content-type'] = 'application/json';
    const start = performance.now(),
      response = await fetch(this.url + route, {
        method,
        headers,
        redirect: 'error',
        body:
          body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
        signal: AbortSignal.timeout(240000),
      });
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';')[0],
        at = pair.indexOf('=');
      if (at > 0) this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    const reader = response.body?.getReader(),
      parts = [];
    let length = 0;
    if (!reader) throw new Error('API响应为空');
    try {
      while (true) {
        const item = await reader.read();
        if (item.done) break;
        length += item.value.length;
        if (length > 5 * 1024 * 1024) throw new Error('API响应过大');
        parts.push(item.value);
      }
    } finally {
      await reader.cancel().catch(() => {});
    }
    const bytes = Buffer.concat(parts);
    if (!allowed.includes(response.status)) throw new Error('API状态不符合预期');
    if (binary)
      return {
        data: bytes,
        status: response.status,
        latencyMs: performance.now() - start,
        traceId: response.headers.get('x-trace-id'),
      };
    const value = JSON.parse(bytes.toString('utf8'));
    if (response.status < 400 && value.code !== 200) throw new Error('业务状态不符合预期');
    return {
      data: value.data,
      status: response.status,
      latencyMs: performance.now() - start,
      traceId: value.traceId ?? null,
    };
  }
  async login(account) {
    identifier(account.tenantId);
    this.csrf = (await this.request('GET', '/api/v1/auth/csrf')).data;
    const result = (await this.request('POST', '/api/v1/auth/login', account)).data;
    if (result.tenantId !== account.tenantId) throw new Error('登录租户不匹配');
    identifier(result.id);
    this.csrf = (await this.request('GET', '/api/v1/auth/csrf')).data;
    return result;
  }
}
function remapAnswer(item, question, gold) {
  if (
    typeof question.answer !== 'string' ||
    !Array.isArray(question.citations) ||
    question.sourcesUnavailable ||
    question.status !== 'SUCCEEDED'
  )
    return null;
  const numbering = new Map();
  for (const cite of question.citations) {
    if (typeof cite.id !== 'string' || numbering.has(cite.id)) throw new Error('回答来源编号无效');
    const binding = gold.get(cite.chunkId);
    numbering.set(
      cite.id,
      binding &&
        binding.fileVersionId === cite.fileVersionId &&
        binding.documentId === cite.documentId
        ? binding.goldId
        : '9999',
    );
  }
  return {
    answer: question.answer.replace(/\[([0-9]+)\]/g, (_, id) => `[${numbering.get(id) ?? '9999'}]`),
    citationIds: question.citations.map((c) => numbering.get(c.id)),
    insufficientEvidence: question.insufficientEvidence,
  };
}
async function execute(config, dataset, outputDir) {
  const credentials = common.readJson(config.credentialsFile, true);
  if (
    !credentials.owner ||
    !credentials.otherTenant ||
    credentials.owner.tenantId === credentials.otherTenant.tenantId
  )
    throw new Error('需要两个自有合成租户账号');
  fs.mkdirSync(outputDir, { mode: 0o700 });
  const owner = new JavaApi(config.javaUrl),
    other = new JavaApi(config.javaUrl);
  const token = common.secret(config.gateway.tokenFile),
    gateUrl = new URL(config.gateway.url);
  gateUrl.pathname = '/__lexflow_eval/';
  async function gateway(method, route, body) {
    const response = await fetch(new URL(route, gateUrl), {
      method,
      redirect: 'error',
      headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
    if (response.status !== 200) throw new Error('授权调用记录器不可用');
    const result = await response.json();
    if (
      route === 'state' &&
      (result.runId !== config.runId || result.configurationSha256 !== common.configHash(config))
    )
      throw new Error('记录器与本次授权不匹配');
    return result;
  }
  const files = [],
    users = [],
    tasks = [],
    observations = [],
    results = [],
    rawAnswers = [],
    cleanup = [],
    prepared = [],
    configured = [];
  let fatal = false;
  let machineDataset = structuredClone(dataset),
    gateState = null;
  async function phase(name) {
    await gateway('POST', 'phase', { phase: name });
  }
  async function wait(task, kind, item) {
    const start = performance.now();
    for (let n = 0; n < 120; n++) {
      const view = (await owner.request('GET', `/api/v1/tasks/${identifier(task.id)}`)).data;
      if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'WAITING_HUMAN'].includes(view.status)) {
        tasks.push({
          item,
          kind,
          taskId: view.id,
          status: view.status,
          attempt: view.attempt,
          stateVersion: null,
          stateVersionSource: 'not exposed by current task API',
          latencyMs: performance.now() - start,
        });
        if (view.status !== 'SUCCEEDED') throw new Error('实际任务未成功');
        return view;
      }
      await delay(1000);
    }
    tasks.push({
      item,
      kind,
      taskId: task.id,
      status: 'OBSERVATION_TIMEOUT',
      latencyMs: performance.now() - start,
    });
    throw new Error('任务观察超时');
  }
  async function material(item, base, caseId, text, goldId) {
    if (text.length > 1000) throw new Error('评估原文超过单切片边界');
    const form = new FormData();
    form.append(
      'file',
      new Blob([text], { type: 'text/plain' }),
      `${item}-${goldId ?? 'distractor'}.txt`,
    );
    const prefix = caseId
      ? `/api/v1/cases/${caseId}/files`
      : `/api/v1/tenant-knowledge-bases/${base}/files`;
    const file = (await owner.request('POST', prefix, form)).data;
    identifier(file.id);
    identifier(file.versionId);
    const deletion = caseId ? `/api/v1/files/${file.id}` : `${prefix}/${file.id}`;
    files.push({ item, fileId: file.id, versionId: file.versionId, deletion, deleted: false });
    const download = caseId ? `/api/v1/files/${file.id}/download` : `${prefix}/${file.id}/download`;
    const bytes = (await owner.request('GET', download, undefined, [200], true)).data;
    if (common.sha(bytes) !== common.sha(Buffer.from(text)))
      throw new Error('实际上传下载原文不一致');
    const parse = caseId
      ? (
          await owner.request('POST', '/api/v1/tasks', {
            caseId,
            type: 'DOCUMENT_PARSE',
            resourceVersionId: file.versionId,
            idempotencyKey: randomUUID(),
          })
        ).data
      : (
          await owner.request('POST', `${prefix}/${file.id}/parse`, {
            fileVersionId: file.versionId,
            idempotencyKey: randomUUID(),
          })
        ).data;
    await wait(parse, 'DOCUMENT_PARSE', item);
    const document = (
      await owner.request('POST', `/api/v1/knowledge-bases/${base}/documents`, {
        parseTaskId: parse.id,
      })
    ).data;
    identifier(document.id);
    const chunks = (
      await owner.request('GET', `/api/v1/knowledge-bases/${base}/documents/${document.id}/chunks`)
    ).data;
    if (!Array.isArray(chunks) || chunks.length !== 1 || chunks[0].text !== text)
      throw new Error('实际切片与固定原文不一致');
    const index = (
      await owner.request('POST', `/api/v1/knowledge-bases/${base}/documents/${document.id}/index`)
    ).data;
    await wait({ id: index.taskId }, 'KNOWLEDGE_INGEST', item);
    return {
      goldId,
      documentId: document.id,
      fileVersionId: file.versionId,
      chunkId: identifier(chunks[0].id),
      fileId: file.id,
      deletion,
      download,
      sourceSha256: common.sha(bytes),
    };
  }
  try {
    await owner.login(credentials.owner);
    await other.login(credentials.otherTenant);
    const before = (await owner.request('GET', '/api/v1/cases?page=1&size=1')).data;
    const bases = (await owner.request('GET', '/api/v1/tenant-knowledge-bases')).data;
    const settings = (await owner.request('GET', '/api/v1/settings/ai')).data;
    if (
      before.total !== 0 ||
      !Array.isArray(bases.items) ||
      bases.items.length !== 0 ||
      !settings.storageReady ||
      !Array.isArray(settings.items) ||
      settings.items.length !== 0
    )
      throw new Error('验收租户必须为空且未配置模型');
    gateState = await gateway('GET', 'state');
    if (gateState.calls !== 0 || gateState.halted) throw new Error('本次记录器已有调用或已终止');
    for (const purpose of ['EMBEDDING', 'LLM']) {
      configured.push(purpose);
      await owner.request('PUT', `/api/v1/settings/ai/${purpose}`, {
        provider: config.provider.name,
        baseUrl: config.gateway.url,
        model: config.models[purpose].model,
        apiKey: token,
      });
    }
    const password = randomBytes(24).toString('hex'),
      username = 'eval-' + randomUUID();
    const user = (
      await owner.request('POST', '/api/v1/organization/users', {
        username,
        password,
        name: 'evaluation uninvolved member',
        role: 'LAWYER',
      })
    ).data;
    identifier(user.id);
    users.push(user.id);
    const uninvolved = new JavaApi(config.javaUrl);
    await uninvolved.login({ username, password, tenantId: credentials.owner.tenantId });
    for (const [ordinal, item] of dataset.cases.entries()) {
      try {
        await phase('prepare:' + item.id);
        const publicScope = item.id === 'public-private-separation';
        const caseId = publicScope
          ? null
          : identifier(
              (
                await owner.request('POST', '/api/v1/cases', {
                  caseNo: `${config.runId.slice(0, 50)}-${ordinal}`,
                  title: item.id,
                  type: 'CIVIL',
                  description: 'Authorized synthetic evaluation only',
                })
              ).data.id,
            );
        const base = identifier(
          (
            await owner.request(
              'POST',
              publicScope ? '/api/v1/tenant-knowledge-bases' : '/api/v1/knowledge-bases',
              publicScope
                ? { name: `${config.runId.slice(0, 50)}-${ordinal}` }
                : { caseId, name: item.id },
            )
          ).data.id,
        );
        const gold = new Map(),
          sources = [],
          excludedChunks = [];
        for (const evidence of item.input.evidence) {
          const source = await material(item.id, base, caseId, evidence.text, evidence.id);
          sources.push(source);
          gold.set(source.chunkId, source);
        }
        if (item.input.evidence.length) {
          const distractor = await material(
            item.id,
            base,
            caseId,
            '无关材料：器材借用登记。没有该问题的合同约定、付款凭证或验收结论。',
            null,
          );
          sources.push(distractor);
        }
        if (item.id === 'effective-version') {
          const obsolete = await material(
            item.id,
            base,
            caseId,
            '旧v1约定：验收后三十日付款；此来源随后被删除，不再作为有效材料。',
            null,
          );
          excludedChunks.push(obsolete.chunkId);
          await owner.request('DELETE', obsolete.deletion);
          files.find((f) => f.fileId === obsolete.fileId).deleted = true;
          await owner.request('GET', obsolete.download, undefined, [403]);
          observations.push({ id: item.id, probe: 'old-source-download', status: 403 });
        }
        if (publicScope) {
          const privateCase = identifier(
            (
              await owner.request('POST', '/api/v1/cases', {
                caseNo: config.runId.slice(0, 50) + '-private',
                title: 'Synthetic secret scope',
                type: 'CIVIL',
              })
            ).data.id,
          );
          const privateBase = identifier(
            (
              await owner.request('POST', '/api/v1/knowledge-bases', {
                caseId: privateCase,
                name: 'Synthetic private distractor',
              })
            ).data.id,
          );
          const secretSource = await material(
            item.id,
            privateBase,
            privateCase,
            '仅属于私有案件的合成记录：虹羽项目实际支付98万元。',
            null,
          );
          excludedChunks.push(secretSource.chunkId);
          machineDataset.cases.find((c) => c.id === item.id).expected.refusalAnswer =
            TENANT_REFUSAL;
        }
        const session = identifier(
          (await owner.request('POST', '/api/v1/ai/sessions', { baseId: base, title: item.id }))
            .data.id,
        );
        prepared.push({
          id: item.id,
          baseId: base,
          caseId,
          sessionId: session,
          sources: sources.map((s) => ({
            goldId: s.goldId,
            chunkId: s.chunkId,
            documentId: s.documentId,
            fileVersionId: s.fileVersionId,
            sourceSha256: s.sourceSha256,
          })),
        });
        // Denials happen before any provider request; prove the same phase has no dispatched intent.
        await phase('permission:' + item.id);
        const beforePermission = (await gateway('GET', 'state')).calls;
        await other.request(
          'POST',
          `/api/v1/knowledge-bases/${base}/search`,
          { question: item.input.question },
          [403],
        );
        await other.request('GET', `/api/v1/ai/sessions/${session}/messages`, undefined, [403]);
        await other.request(
          'POST',
          `/api/v1/ai/sessions/${session}/messages`,
          { requestId: randomUUID(), question: item.input.question },
          [403],
        );
        if (caseId)
          await uninvolved.request(
            'POST',
            `/api/v1/knowledge-bases/${base}/search`,
            { question: item.input.question },
            [403],
          );
        if (caseId)
          await uninvolved.request(
            'POST',
            `/api/v1/ai/sessions/${session}/messages`,
            { requestId: randomUUID(), question: item.input.question },
            [403],
          );
        const afterPermission = (await gateway('GET', 'state')).calls;
        observations.push({
          id: item.id,
          probe: 'cross-tenant and uninvolved-case access',
          status: 403,
          providerAttemptDelta: afterPermission - beforePermission,
          passed: beforePermission === afterPermission,
        });
        if (beforePermission !== afterPermission) throw new Error('权限拒绝期间发生模型请求');
        await phase('retrieve:' + item.id);
        const retrieved = await owner.request('POST', `/api/v1/knowledge-bases/${base}/search`, {
          question: item.input.question,
        });
        const found = retrieved.data.citations.map((c) => c.chunkId),
          relevant = sources.filter((s) => s.goldId !== null).map((s) => s.chunkId);
        const matched = found.filter((id) => relevant.includes(id)).length;
        observations.push({
          id: item.id,
          probe: 'independent authorized retrieval',
          rankedChunkIds: found,
          expectedChunkIds: relevant,
          recall: relevant.length ? matched / relevant.length : null,
          precision: found.length ? matched / found.length : null,
          latencyMs: retrieved.latencyMs,
          excludedChunkIds: excludedChunks,
          excludedSourcesAbsent: excludedChunks.every((id) => !found.includes(id)),
          source:
            'Java search API; not claimed to be the subsequent question internal candidate list',
        });
        if (excludedChunks.some((id) => found.includes(id)))
          throw new Error('检索包含已删除或其它范围的来源');
        await phase('answer:' + item.id);
        for (const previous of item.input.previousQuestions)
          await owner.request('POST', `/api/v1/ai/sessions/${session}/messages`, {
            requestId: randomUUID(),
            question: previous,
          });
        const answer = await owner.request('POST', `/api/v1/ai/sessions/${session}/messages`, {
          requestId: randomUUID(),
          question: item.input.question,
        });
        const normalized = remapAnswer(item, answer.data, gold);
        results.push({ id: item.id, output: normalized });
        rawAnswers.push({
          id: item.id,
          questionRecord: answer.data,
          latencyMs: answer.latencyMs,
          traceId: answer.traceId,
        });
        observations.push({
          id: item.id,
          probe: 'actual question persistence',
          status: answer.data.status,
          questionId: answer.data.id,
          latencyMs: answer.latencyMs,
        });
        const state = await gateway('GET', 'state');
        if (state.halted) throw new Error('调用记录器发现未知用量或预算边界');
      } catch {
        observations.push({
          id: item.id,
          probe: 'execution',
          passed: false,
          error: 'evaluation path did not complete',
        });
        if ((await gateway('GET', 'state')).halted) {
          fatal = true;
          break;
        }
      }
    }
  } catch {
    fatal = true;
    observations.push({
      probe: 'execution',
      passed: false,
      error: 'evaluation precondition or runtime failed',
    });
  } finally {
    try {
      await phase(null);
    } catch {
      cleanup.push({ kind: 'gateway-phase', cleared: false });
    }
    for (const file of files)
      if (!file.deleted) {
        try {
          await owner.request('DELETE', file.deletion);
          file.deleted = true;
          cleanup.push({ kind: 'file', id: file.fileId, logicalDeleted: true });
        } catch {
          cleanup.push({ kind: 'file', id: file.fileId, logicalDeleted: false });
        }
      }
    for (const id of users) {
      try {
        await owner.request('PATCH', `/api/v1/organization/users/${id}`, {
          status: 'DISABLED',
          role: 'LAWYER',
        });
        cleanup.push({ kind: 'user', id, disabled: true });
      } catch {
        cleanup.push({ kind: 'user', id, disabled: false });
      }
    }
    for (const purpose of configured)
      try {
        await owner.request('DELETE', `/api/v1/settings/ai/${purpose}`);
        cleanup.push({ kind: 'model-configuration', purpose, removed: true });
      } catch {
        cleanup.push({ kind: 'model-configuration', purpose, removed: false });
      }
    try {
      gateState = await gateway('GET', 'state');
    } catch {
      gateState = null;
    }
  }
  const scores = evaluate(machineDataset, { schemaVersion: 1, cases: results }),
    permissionChecks = observations.filter((o) => o.providerAttemptDelta !== undefined);
  const expectedMaterialCount = dataset.cases.reduce(
    (n, c) =>
      n +
      c.input.evidence.length +
      (c.input.evidence.length ? 1 : 0) +
      (c.id === 'effective-version' ? 1 : 0) +
      (c.id === 'public-private-separation' ? 1 : 0),
    0,
  );
  const expectedTaskCount = expectedMaterialCount * 2;
  common.writeNew(path.join(outputDir, 'answers.private.json'), {
    schemaVersion: 1,
    cases: results,
  });
  common.writeNew(path.join(outputDir, 'raw-question-records.private.json'), {
    schemaVersion: 1,
    cases: rawAnswers,
  });
  common.writeNew(path.join(outputDir, 'human-review.private.json'), {
    schemaVersion: 1,
    cases: dataset.cases.map((c) => ({
      id: c.id,
      accepted: null,
      reviewer: '',
      reviewedAt: '',
      reason: '',
    })),
  });
  const report = {
    schemaVersion: 1,
    runId: config.runId,
    evaluationKind: 'actual-java-api-with-authorized-provider-gateway',
    datasetSha256: config.datasetSha256,
    runtimeDatasetSha256: common.sha(JSON.stringify(machineDataset)),
    configurationSha256: common.configHash(config),
    fatal,
    expectedQuestions: dataset.cases.length,
    completedQuestions: results.length,
    taskSuccessRate: expectedTaskCount
      ? tasks.filter((t) => t.status === 'SUCCEEDED').length / expectedTaskCount
      : null,
    taskDenominatorSource:
      'Two planned parse/index tasks for every fixed material, per-case distractor, obsolete-source probe and private-scope probe; unstarted tasks remain in the denominator.',
    expectedTaskCount,
    observedTaskCount: tasks.length,
    tasks,
    prepared,
    observations,
    scores,
    permissionIsolationPassed:
      permissionChecks.length === dataset.cases.length && permissionChecks.every((p) => p.passed),
    providerEvidence: gateState,
    humanSemanticReviewPassed: false,
    billingVerified: false,
    legalQualityAccepted: false,
    cleanup,
    isolatedRuntimeTeardownVerified: false,
    remainingResources:
      'Prepared case/base/session/audit/task metadata and independent infrastructure require exact owned runtime teardown; logical file deletion is not physical cleanup proof.',
  };
  common.writeNew(path.join(outputDir, 'report.json'), report);
  return report;
}
async function main(args) {
  const options = new Map();
  let live = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--execute') {
      if (live) throw new Error('duplicate execution flag');
      live = true;
    } else if (
      ['--config', '--output', '--dataset'].includes(args[i]) &&
      args[i + 1] &&
      !options.has(args[i])
    ) {
      options.set(args[i], args[++i]);
    } else throw new Error('invalid option');
  }
  if (!options.has('--output')) throw new Error('new output path required');
  const datasetBytes = common.readFile(
      options.get('--dataset') ?? path.join(__dirname, 'material-grounding-v1.json'),
    ),
    dataset = JSON.parse(datasetBytes.toString('utf8'));
  evaluate(dataset, { schemaVersion: 1, cases: [] });
  const digest = common.sha(datasetBytes);
  if (!live) {
    let hash = null;
    if (options.has('--config')) hash = common.configHash(common.readJson(options.get('--config')));
    const plan = {
      schemaVersion: 1,
      evaluationKind: 'dry-run-no-network',
      datasetSha256: digest,
      configurationSha256: hash,
      caseIds: dataset.cases.map((c) => c.id),
      questions: dataset.cases.length,
      evidenceFiles: dataset.cases.reduce((n, c) => n + c.input.evidence.length, 0),
      actualProviderCalls: 0,
      networkRequests: 0,
      legalQualityAccepted: false,
      humanSemanticReviewPassed: false,
      requiredAuthorization: [
        'supplier and HTTPS endpoint',
        'exact Embedding/LLM models and response snapshots',
        'approved tokenizer and input/output bounds',
        'per-purpose/total call count',
        'USD conservative reservation and reviewed rates',
        'private credentials and owned fresh runtime',
        'human approval record bound to configuration hash',
      ],
      caveat:
        'This plan does not run parsing, Milvus, permissions or a provider; the 12 artificial answers are never used as actual execution evidence.',
    };
    common.writeNew(path.resolve(options.get('--output')), plan);
    process.stdout.write(
      JSON.stringify({
        mode: 'dry-run',
        questions: dataset.cases.length,
        actualProviderCalls: 0,
        networkRequests: 0,
      }) + '\n',
    );
    return;
  }
  if (!options.has('--config')) throw new Error('approved configuration required');
  const config = common.validate(common.readJson(options.get('--config')), digest);
  const report = await execute(config, dataset, path.resolve(options.get('--output')));
  process.stdout.write(
    JSON.stringify({
      mode: 'execute',
      completed: report.completedQuestions,
      total: report.expectedQuestions,
      legalQualityAccepted: false,
      billingVerified: false,
    }) + '\n',
  );
  if (report.fatal || report.scores.failed || !report.permissionIsolationPassed)
    process.exitCode = 1;
}
if (require.main === module)
  main(process.argv.slice(2)).catch(() => {
    process.stderr.write('评估未执行或未完成：授权、配置、输入或输出无效。\n');
    process.exitCode = 2;
  });
module.exports = { JavaApi, remapAnswer, execute, main };
