const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { parsePlainText } = require('../dist/parsers/plain-text.parser');
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
test('提取中文原文和段落，不生成结论或页码', () => {
  const text = '合同材料\r\n第一行\r\n\r\n第二段：付款条件。';
  const bytes = Buffer.from(text);
  const result = parsePlainText(bytes, 'text/plain', digest(bytes));
  assert.equal(result.text, text);
  assert.equal(result.paragraphs.length, 2);
  assert.equal(result.paragraphs[1].text, '第二段：付款条件。');
  assert.equal(result.sourceSha256, digest(bytes));
});
test('损坏摘要、错误编码、二进制、空正文和未支持格式均明确失败', () => {
  const bytes = Buffer.from('正常材料');
  assert.throws(() => parsePlainText(bytes, 'text/plain', 'a'.repeat(64)), /摘要/);
  assert.throws(() => parsePlainText(bytes, 'application/pdf', digest(bytes)), /格式/);
  for (const bad of [Buffer.from([0xff]), Buffer.from([0, 1, 2]), Buffer.from(' \n ')])
    assert.throws(() => parsePlainText(bad, 'text/plain', digest(bad)));
});
