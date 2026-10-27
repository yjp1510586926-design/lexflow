const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');

test('工作流配置兼容默认/测试库和独立实例；只建惰性pool不连接数据库', async () => {
  for (const database of [
    'lexflow_ai',
    'lexflow_ai_test',
    'lexflow_ai_install_abc123',
    'lexflow_ai_' + 'a'.repeat(53),
  ]) {
    const pool = workflowPool(
      `mysql://lexflow_ai_worker:synthetic-password@127.0.0.1:9/${database}`,
    );
    assert.equal(pool.pool.config.connectionConfig.database, database);
    assert.equal(pool.pool.config.connectionConfig.multipleStatements, false);
    await pool.end();
  }
});
test('业务库、root/Java账号、超长或非法工作流库在连接前拒绝', () => {
  for (const database of [
    'lexflow',
    'other_database',
    'lexflow_ai_',
    'lexflow_ai_Bad',
    'lexflow_ai_' + 'a'.repeat(54),
  ])
    assert.throws(() =>
      workflowPool(`mysql://lexflow_ai:synthetic-password@127.0.0.1:9/${database}`),
    );
  for (const account of ['root', 'lexflow', '%72oot', '%6cexflow'])
    assert.throws(() =>
      workflowPool(`mysql://${account}:synthetic-password@127.0.0.1:9/lexflow_ai_install_abc123`),
    );
});
test('初始化标识注入、错前缀及长度超限在任何Docker调用前拒绝', () => {
  const script = path.resolve(__dirname, '../../infra/mysql/init-ai-workflow.sh');
  for (const [name, value] of [
    ['AI_WORKFLOW_MYSQL_DATABASE', 'lexflow'],
    ['AI_WORKFLOW_MYSQL_DATABASE', 'lexflow_ai_;DROP'],
    ['AI_WORKFLOW_MYSQL_DATABASE', 'lexflow_ai_' + 'a'.repeat(54)],
    ['AI_WORKFLOW_MYSQL_USER', 'root'],
    ['AI_WORKFLOW_MYSQL_USER', "lexflow_ai_'x"],
    ['AI_WORKFLOW_MYSQL_USER', 'lexflow_ai_' + 'a'.repeat(22)],
  ]) {
    const result = spawnSync('/bin/sh', [script], {
      encoding: 'utf8',
      env: {
        PATH: '/usr/bin:/bin',
        AI_WORKFLOW_MYSQL_PASSWORD: 'synthetic-password-123',
        AI_WORKFLOW_MYSQL_DATABASE: 'lexflow_ai_install_abc123',
        AI_WORKFLOW_MYSQL_USER: 'lexflow_ai_worker',
        [name]: value,
      },
    });
    assert.equal(result.status, 1);
    assert.doesNotMatch(result.stderr, /docker|not found/);
    assert.doesNotMatch(result.stderr, /synthetic-password|DROP/);
  }
});
