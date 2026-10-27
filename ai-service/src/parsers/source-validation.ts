import { createHash } from 'node:crypto';

export const MAX_SOURCE_BYTES = 50 * 1024 * 1024;
export const MAX_TEXT_LENGTH = 1_000_000;

export function verifySource(bytes: Buffer, expectedSha256: string): string {
  if (bytes.length === 0 || bytes.length > MAX_SOURCE_BYTES)
    throw new Error('文件为空或超过解析上限');
  const hash = createHash('sha256').update(bytes).digest('hex');
  if (!/^[a-fA-F0-9]{64}$/.test(expectedSha256) || hash !== expectedSha256.toLowerCase())
    throw new Error('文件摘要校验失败');
  return hash;
}

export function requireExtractedText(text: string, format: string): string {
  if (!text.trim()) throw new Error(`${format}没有可提取的正文`);
  if (text.length > MAX_TEXT_LENGTH) throw new Error('解析正文超过 100 万字符上限');
  return text;
}
