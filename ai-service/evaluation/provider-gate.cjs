#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');
const { getEncoding } = require('js-tiktoken');
const common = require('./real-evaluation-common.cjs');

/** Only an explicitly approved isolated evaluation may start this forwarding process. */
function createGate(config, options = {}) {
  const token = common.secret(config.gateway.tokenFile),
    key = common.secret(config.provider.keyFile);
  fs.mkdirSync(config.gateway.ledgerDirectory, { mode: 0o700 });
  const ledger = fs.openSync(
    path.join(config.gateway.ledgerDirectory, 'provider-calls.jsonl'),
    fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL,
    0o600,
  );
  const models = Object.fromEntries(
    Object.entries(config.models).map(([purpose, m]) => [
      purpose,
      { ...m, tokenizer: getEncoding(m.encoding) },
    ]),
  );
  const state = {
    runId: config.runId,
    configurationSha256: common.configHash(config),
    phase: null,
    halted: false,
    calls: 0,
    heldMicros: 0,
    estimatedMicros: 0,
    usageUnknown: 0,
    byPurpose: { EMBEDDING: 0, LLM: 0 },
    events: [],
  };
  function append(event) {
    fs.writeSync(ledger, JSON.stringify(event) + '\n');
    fs.fsyncSync(ledger);
    state.events.push(event);
  }
  function inspect(purpose, body) {
    const m = models[purpose];
    if (
      !body ||
      typeof body !== 'object' ||
      Array.isArray(body) ||
      body.model !== m.model ||
      body.stream === true ||
      body.tools ||
      body.functions ||
      body.modalities ||
      body.audio ||
      (body.n !== undefined && body.n !== 1)
    )
      throw new Error('request out of scope');
    const allowed =
      purpose === 'EMBEDDING'
        ? ['model', 'input', 'encoding_format', 'dimensions', 'user']
        : [
            'model',
            'messages',
            'temperature',
            'top_p',
            'frequency_penalty',
            'presence_penalty',
            'seed',
            'response_format',
            'max_tokens',
            'max_completion_tokens',
            'stream',
            'n',
            'service_tier',
            'user',
            'metadata',
            'stop',
          ];
    if (Object.keys(body).some((k) => !allowed.includes(k)))
      throw new Error('unsupported request field');
    if (
      purpose === 'LLM' &&
      (!body.response_format ||
        Object.keys(body.response_format).length !== 1 ||
        body.response_format.type !== 'json_object')
    )
      throw new Error('evaluation response format required');
    let inputTokens = 0,
      maxOutput = 0;
    if (purpose === 'EMBEDDING') {
      const texts = typeof body.input === 'string' ? [body.input] : body.input;
      if (
        !Array.isArray(texts) ||
        !texts.length ||
        texts.length > 50 ||
        texts.some((v) => typeof v !== 'string' || !v.length)
      )
        throw new Error('embedding input out of scope');
      inputTokens = texts.reduce((sum, v) => sum + m.tokenizer.encode(v).length, 0);
    } else {
      if (
        !Array.isArray(body.messages) ||
        !body.messages.length ||
        body.messages.length > 20 ||
        body.messages.some(
          (x) =>
            !x ||
            !['system', 'user', 'assistant'].includes(x.role) ||
            typeof x.content !== 'string' ||
            Object.keys(x).some((k) => !['role', 'content'].includes(k)),
        )
      )
        throw new Error('chat input out of scope');
      inputTokens = body.messages.reduce(
        (sum, x) =>
          sum +
          m.tokenizer.encode(x.content).length +
          m.tokenizer.encode(x.role).length +
          m.messageOverheadTokens,
        m.messageOverheadTokens,
      );
      if (body.max_tokens !== undefined && body.max_completion_tokens !== undefined)
        throw new Error('ambiguous output bound');
      maxOutput = common.integer(
        body.max_completion_tokens ?? body.max_tokens,
        1,
        m.maxOutputTokens,
      );
      if (body.service_tier !== undefined && body.service_tier !== 'default')
        throw new Error('processing tier out of scope');
    }
    if (inputTokens > m.maxInputTokens) throw new Error('input token bound exceeded');
    const reservation = common.cost(m.maxInputTokens, purpose === 'LLM' ? m.maxOutputTokens : 0, m);
    if (
      state.halted ||
      !state.phase ||
      state.calls >= config.limits.maxCalls ||
      state.byPurpose[purpose] >= m.maxCalls ||
      state.heldMicros + reservation > config.limits.budgetMicrosUSD
    )
      throw new Error('evaluation limit reached');
    return { m, inputTokens, maxOutput, reservation };
  }
  async function forward(purpose, bytes) {
    const body = JSON.parse(bytes.toString('utf8')),
      bounds = inspect(purpose, body),
      phase = state.phase;
    const sequence = ++state.calls;
    state.byPurpose[purpose]++;
    state.heldMicros += bounds.reservation;
    append({
      kind: 'INTENT',
      sequence,
      runId: config.runId,
      phase,
      purpose,
      model: body.model,
      provider: config.provider.name,
      requestSha256: common.sha(bytes),
      estimatedInputTokens: bounds.inputTokens,
      reservedMicros: bounds.reservation,
      at: new Date().toISOString(),
    });
    const start = performance.now();
    let status = 0,
      responseBytes = Buffer.alloc(0),
      usage = null,
      requestId = null,
      returnedModel = null,
      estimate = null;
    try {
      const endpoint = new URL(
        config.provider.baseUrl.replace(/\/$/, '') +
          (purpose === 'LLM' ? '/chat/completions' : '/embeddings'),
      );
      if (purpose === 'LLM') body.service_tier = 'default';
      const response = await (options.fetch ?? fetch)(endpoint, {
        method: 'POST',
        redirect: 'error',
        headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(config.limits.requestTimeoutMs),
      });
      status = response.status;
      const requestHeader = response.headers.get('x-request-id');
      if (requestHeader && /^[A-Za-z0-9._-]{1,200}$/.test(requestHeader)) requestId = requestHeader;
      const reader = response.body?.getReader(),
        parts = [];
      let length = 0;
      if (!reader) throw new Error('empty response');
      try {
        while (true) {
          const item = await reader.read();
          if (item.done) break;
          length += item.value.length;
          if (length > 4 * 1024 * 1024) throw new Error('response too large');
          parts.push(item.value);
        }
      } finally {
        await reader.cancel().catch(() => {});
      }
      responseBytes = Buffer.concat(parts);
      const result = JSON.parse(responseBytes.toString('utf8'));
      returnedModel =
        typeof result.model === 'string' && result.model.length <= 200 ? result.model : null;
      if (status === 200 && bounds.m.responseModels.includes(returnedModel)) {
        const u = result.usage;
        const input = u?.prompt_tokens,
          output = purpose === 'LLM' ? u?.completion_tokens : 0,
          total = u?.total_tokens;
        if (
          Number.isSafeInteger(input) &&
          input >= 0 &&
          Number.isSafeInteger(output) &&
          output >= 0 &&
          Number.isSafeInteger(total) &&
          total === input + output
        ) {
          usage = { inputTokens: input, outputTokens: output, totalTokens: total };
          estimate = common.cost(input, output, bounds.m);
          if (
            input > bounds.m.maxInputTokens ||
            output > (purpose === 'LLM' ? bounds.m.maxOutputTokens : 0) ||
            estimate > bounds.reservation
          )
            state.halted = true;
        }
      }
      if (!usage) {
        state.usageUnknown++;
        state.halted = true;
      } else {
        state.estimatedMicros += estimate;
        state.heldMicros -= bounds.reservation - estimate;
      }
      append({
        kind: 'RESULT',
        sequence,
        runId: config.runId,
        phase,
        purpose,
        status,
        requestId,
        returnedModel,
        usage,
        responseSha256: common.sha(responseBytes),
        latencyMs: performance.now() - start,
        estimatedMicros: estimate,
        billingVerified: false,
        at: new Date().toISOString(),
      });
      return {
        status,
        bytes:
          status === 200
            ? responseBytes
            : Buffer.from('{"error":{"message":"evaluation provider unavailable"}}'),
      };
    } catch {
      state.halted = true;
      state.usageUnknown++;
      append({
        kind: 'UNKNOWN',
        sequence,
        runId: config.runId,
        phase,
        purpose,
        status,
        requestId,
        responseSha256: responseBytes.length ? common.sha(responseBytes) : null,
        latencyMs: performance.now() - start,
        estimatedMicros: null,
        billingVerified: false,
        at: new Date().toISOString(),
      });
      return {
        status: 503,
        bytes: Buffer.from('{"error":{"message":"evaluation response unavailable"}}'),
      };
    }
  }
  async function handler(req, res) {
    const send = (status, value) => {
      res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify(value));
    };
    if (!common.equal(req.headers.authorization, 'Bearer ' + token)) {
      send(401, { error: 'evaluation authorization required' });
      return;
    }
    try {
      if (req.url === '/__lexflow_eval/state' && req.method === 'GET') {
        send(200, state);
        return;
      }
      const parts = [];
      let length = 0;
      for await (const part of req) {
        length += part.length;
        if (length > config.limits.maxRequestBytes) throw new Error('request too large');
        parts.push(part);
      }
      const bytes = Buffer.concat(parts);
      if (req.url === '/__lexflow_eval/phase' && req.method === 'POST') {
        const body = JSON.parse(bytes.toString('utf8'));
        if (
          Object.keys(body).length !== 1 ||
          !Object.hasOwn(body, 'phase') ||
          (body.phase !== null && !/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,99}$/.test(body.phase))
        )
          throw new Error('phase out of scope');
        state.phase = body.phase;
        send(200, { accepted: true });
        return;
      }
      const purpose =
        req.url === '/v1/chat/completions'
          ? 'LLM'
          : req.url === '/v1/embeddings'
            ? 'EMBEDDING'
            : null;
      if (req.method !== 'POST' || !purpose) {
        send(404, { error: 'evaluation route unavailable' });
        return;
      }
      const result = await forward(purpose, bytes);
      res.writeHead(result.status, {
        'content-type': 'application/json',
        'cache-control': 'no-store',
      });
      res.end(result.bytes);
    } catch {
      send(429, { error: 'evaluation scope or budget rejected' });
    }
  }
  return { state, forward, handler, close: () => fs.closeSync(ledger) };
}
async function main(args) {
  if (args.length !== 2 || args[0] !== '--execute-config')
    throw new Error('explicit authorized configuration required');
  const config = common.readJson(args[1]),
    dataset = common.readFile(path.join(__dirname, 'material-grounding-v1.json'));
  common.validate(config, common.sha(dataset));
  const gate = createGate(config),
    url = new URL(config.gateway.url);
  const server = https.createServer(
    {
      key: common.readFile(config.gateway.tlsKeyFile, true, 32768),
      cert: common.readFile(config.gateway.tlsCertFile, false, 32768),
    },
    gate.handler,
  );
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(Number(url.port), url.hostname, resolve);
  });
  process.stdout.write(
    JSON.stringify({
      runId: config.runId,
      ready: true,
      configurationSha256: common.configHash(config),
    }) + '\n',
  );
  const close = () =>
    server.close(() => {
      gate.close();
      process.exit(0);
    });
  process.once('SIGTERM', close);
  process.once('SIGINT', close);
}
if (require.main === module)
  main(process.argv.slice(2)).catch(() => {
    process.stderr.write('评估转发器未启动：授权、配置或私密文件无效；未执行模型调用。\n');
    process.exitCode = 2;
  });
module.exports = { createGate };
