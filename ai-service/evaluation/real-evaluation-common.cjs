'use strict';
const fs = require('node:fs');
const { createHash, timingSafeEqual } = require('node:crypto');
const ID = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$/;
function sha(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
function canonical(value) {
  return JSON.stringify(value, (_, v) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(
          Object.keys(v)
            .sort()
            .map((k) => [k, v[k]]),
        )
      : v,
  );
}
function configHash(config) {
  const { authorizationRecordFile, ...approved } = config;
  return sha(canonical(approved));
}
function object(value, keys) {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).length !== keys.length ||
    keys.some((k) => !Object.hasOwn(value, k))
  )
    throw new Error('评估配置字段无效');
}
function integer(value, min, max) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error('评估上限无效');
  return value;
}
function text(value, max = 200) {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\p{Cc}]/u.test(value))
    throw new Error('评估配置文本无效');
  return value;
}
function readFile(filename, privateFile = false, max = 5 * 1024 * 1024) {
  const fd = fs.openSync(filename, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
  try {
    const s = fs.fstatSync(fd);
    if (
      !s.isFile() ||
      s.size > max ||
      s.nlink !== 1 ||
      (privateFile &&
        ((s.mode & 0o077) !== 0 ||
          (typeof process.getuid === 'function' && s.uid !== process.getuid())))
    )
      throw new Error('评估输入文件权限或大小无效');
    const bytes = fs.readFileSync(fd);
    if (bytes.length > max) throw new Error('评估输入过大');
    return bytes;
  } finally {
    fs.closeSync(fd);
  }
}
function readJson(filename, privateFile = false) {
  return JSON.parse(readFile(filename, privateFile).toString('utf8'));
}
function writeNew(filename, value) {
  const fd = fs.openSync(
    filename,
    fs.constants.O_WRONLY |
      fs.constants.O_CREAT |
      fs.constants.O_EXCL |
      (fs.constants.O_NOFOLLOW || 0),
    0o600,
  );
  try {
    fs.writeFileSync(fd, JSON.stringify(value, null, 2) + '\n');
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
}
function secret(filename) {
  const value = readFile(filename, true, 8192).toString('utf8').trim();
  if (value.length < 24 || value.length > 4096 || /[\r\n]/.test(value))
    throw new Error('评估私密文件无效');
  return value;
}
function equal(a, b) {
  const x = Buffer.from(a ?? ''),
    y = Buffer.from(b ?? '');
  return x.length === y.length && y.length > 0 && timingSafeEqual(x, y);
}
function url(value, local = false) {
  let u;
  try {
    u = new URL(value);
  } catch {
    throw new Error('评估地址无效');
  }
  if (
    u.username ||
    u.password ||
    u.search ||
    u.hash ||
    (u.protocol !== 'https:' && !(local && u.protocol === 'http:')) ||
    (local && !['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname))
  )
    throw new Error('评估地址未限定');
  return u;
}
function validate(config, datasetSha) {
  object(config, [
    'schemaVersion',
    'authorized',
    'runId',
    'datasetSha256',
    'javaUrl',
    'credentialsFile',
    'authorizationRecordFile',
    'ownedRuntime',
    'provider',
    'models',
    'limits',
    'gateway',
    'pricing',
  ]);
  if (
    config.schemaVersion !== 1 ||
    config.authorized !== true ||
    typeof config.runId !== 'string' ||
    !ID.test(config.runId) ||
    config.datasetSha256 !== datasetSha
  )
    throw new Error('真实模型未获本次完整授权');
  url(config.javaUrl, true);
  text(config.credentialsFile, 4096);
  text(config.authorizationRecordFile, 4096);
  object(config.ownedRuntime, ['database', 'aiDatabase', 'bucket', 'vhost', 'redisNamespace']);
  if (
    !/^lexflow_eval_[a-f0-9]{32}$/.test(config.ownedRuntime.database) ||
    !/^lexflow_ai_[a-f0-9]{32}$/.test(config.ownedRuntime.aiDatabase) ||
    ['bucket', 'vhost', 'redisNamespace'].some(
      (k) => !config.ownedRuntime[k]?.includes(config.runId),
    )
  )
    throw new Error('必须使用本次独立验收实例');
  object(config.provider, ['name', 'baseUrl', 'keyFile']);
  text(config.provider.name);
  url(config.provider.baseUrl);
  text(config.provider.keyFile, 4096);
  object(config.models, ['EMBEDDING', 'LLM']);
  for (const [purpose, m] of Object.entries(config.models)) {
    object(
      m,
      purpose === 'LLM'
        ? [
            'model',
            'responseModels',
            'encoding',
            'maxInputTokens',
            'maxOutputTokens',
            'maxCalls',
            'inputMicrosPerMillion',
            'outputMicrosPerMillion',
            'messageOverheadTokens',
          ]
        : [
            'model',
            'responseModels',
            'encoding',
            'maxInputTokens',
            'maxCalls',
            'inputMicrosPerMillion',
            'messageOverheadTokens',
          ],
    );
    text(m.model);
    if (
      !Array.isArray(m.responseModels) ||
      !m.responseModels.length ||
      m.responseModels.length > 10 ||
      m.responseModels.some((v) => typeof v !== 'string' || !v || v.length > 200) ||
      !['cl100k_base', 'o200k_base'].includes(m.encoding)
    )
      throw new Error('模型及tokenizer须明确审阅');
    integer(m.maxInputTokens, 1, 128000);
    integer(m.maxCalls, 1, 200);
    integer(m.inputMicrosPerMillion, 1, 1000000000);
    integer(m.messageOverheadTokens, purpose === 'LLM' ? 32 : 0, 1024);
    if (purpose === 'LLM') {
      integer(m.maxOutputTokens, 1, 32768);
      integer(m.outputMicrosPerMillion, 1, 1000000000);
    }
  }
  object(config.limits, ['maxCalls', 'budgetMicrosUSD', 'maxRequestBytes', 'requestTimeoutMs']);
  integer(config.limits.maxCalls, 1, 200);
  integer(config.limits.budgetMicrosUSD, 1, 1000000000000);
  integer(config.limits.maxRequestBytes, 1024, 1048576);
  integer(config.limits.requestTimeoutMs, 1000, 120000);
  object(config.gateway, ['url', 'tlsKeyFile', 'tlsCertFile', 'tokenFile', 'ledgerDirectory']);
  const g = url(config.gateway.url, true);
  if (g.protocol !== 'https:' || !g.port || g.pathname !== '/v1')
    throw new Error('评估转发器必须为明确端口的本机HTTPS/v1');
  for (const key of ['tlsKeyFile', 'tlsCertFile', 'tokenFile', 'ledgerDirectory'])
    text(config.gateway[key], 4096);
  object(config.pricing, ['sourceUrl', 'reviewedAt']);
  url(config.pricing.sourceUrl);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(config.pricing.reviewedAt)) throw new Error('定价核对日期无效');
  const approval = readJson(config.authorizationRecordFile, true);
  object(approval, ['runId', 'approvedBy', 'approvedAt', 'recordReference', 'configurationSha256']);
  if (
    approval.runId !== config.runId ||
    approval.configurationSha256 !== configHash(config) ||
    !Number.isFinite(Date.parse(approval.approvedAt))
  )
    throw new Error('授权记录与本次配置不匹配');
  text(approval.approvedBy);
  text(approval.recordReference, 1000);
  return config;
}
function cost(input, output, model) {
  return Number(
    (BigInt(input) * BigInt(model.inputMicrosPerMillion) +
      BigInt(output) * BigInt(model.outputMicrosPerMillion ?? 0) +
      999999n) /
      1000000n,
  );
}
function humanGate(review, ids) {
  return (
    !!review &&
    review.schemaVersion === 1 &&
    Array.isArray(review.cases) &&
    review.cases.length === ids.length &&
    new Set(review.cases.map((x) => x.id)).size === ids.length &&
    ids.every((id) => {
      const r = review.cases.find((x) => x.id === id);
      return (
        r &&
        r.accepted === true &&
        typeof r.reviewer === 'string' &&
        r.reviewer.trim().length > 0 &&
        typeof r.reason === 'string' &&
        r.reason.trim().length >= 8 &&
        Number.isFinite(Date.parse(r.reviewedAt))
      );
    })
  );
}
module.exports = {
  sha,
  configHash,
  readFile,
  readJson,
  writeNew,
  secret,
  equal,
  url,
  validate,
  cost,
  humanGate,
  integer,
  text,
};
