import { requireExtractedText, verifySource } from './source-validation';

export interface ParsedDocument {
  schemaVersion: '1';
  mimeType: string;
  sourceSha256: string;
  text: string;
  paragraphs?: { index: number; text: string }[];
  pages?: { number: number; text: string }[];
}

/** 无模型参与的原文提取。保留原文，不生成摘要、法律结论或虚构页码。 */
export function parsePlainText(
  bytes: Buffer,
  mimeType: string,
  expectedSha256: string,
): ParsedDocument {
  if (mimeType !== 'text/plain') throw new Error('该格式尚未接入解析器');
  const hash = verifySource(bytes, expectedSha256);
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new Error('文件不是有效的 UTF-8 文本');
  }
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/u.test(text))
    throw new Error('文件包含二进制控制字符');
  requireExtractedText(text, 'TXT 文件');
  const paragraphs = text
    .split(/\r?\n\s*\r?\n/u)
    .filter((part) => part.trim())
    .map((part, index) => ({ index: index + 1, text: part }));
  return { schemaVersion: '1', mimeType: 'text/plain', sourceSha256: hash, text, paragraphs };
}
