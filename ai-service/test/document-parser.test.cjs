const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { createHash } = require('node:crypto');
const { parseDocument } = require('../dist/parsers/document.parser');

const fixture = (name) => readFileSync(join(__dirname, 'fixtures', name));
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

test('PDF 提取真实页码与每页文字，不生成法律分析', async () => {
  const bytes = fixture('two-page.pdf');
  const result = await parseDocument(bytes, 'application/pdf', digest(bytes));
  assert.deepEqual(result.pages, [
    { number: 1, text: 'Contract page one' },
    { number: 2, text: 'Payment page two' },
  ]);
  assert.match(result.text, /【第 2 页】\nPayment page two/);
  assert.equal(result.sourceSha256, digest(bytes));
});

test('DOCX 提取中文段落且不包含模板 XML', async () => {
  const bytes = fixture('two-paragraph.docx');
  const result = await parseDocument(bytes, DOCX, digest(bytes));
  assert.equal(result.text, '测试合同\n\n付款条款：验收后付款。');
  assert.deepEqual(
    result.paragraphs.map((p) => p.index),
    [1, 2],
  );
  assert.equal(result.sourceSha256, digest(bytes));
});

test('PDF/DOCX 摘要不匹配、损坏文件和不支持格式明确失败', async () => {
  const pdf = fixture('two-page.pdf');
  const docx = fixture('two-paragraph.docx');
  for (const [bytes, mime] of [
    [pdf, 'application/pdf'],
    [docx, DOCX],
  ]) {
    await assert.rejects(parseDocument(bytes, mime, 'a'.repeat(64)), /摘要/);
    await assert.rejects(
      parseDocument(Buffer.from('broken'), mime, digest(Buffer.from('broken'))),
      /无法解析/,
    );
  }
  await assert.rejects(parseDocument(pdf, 'image/png', digest(pdf)), /格式/);
});

test('无文字层 PDF 不返回成功解析结果', async () => {
  const bytes = fixture('no-text-layer.pdf');
  await assert.rejects(parseDocument(bytes, 'application/pdf', digest(bytes)), /文字层/);
});
