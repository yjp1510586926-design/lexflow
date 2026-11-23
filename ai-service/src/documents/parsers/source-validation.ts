import { createHash } from 'node:crypto';

/** 所有支持格式共用的原文件字节上限；先做大小检查，再进行摘要或格式解析。 */
export const MAX_SOURCE_BYTES = 50 * 1024 * 1024;
/** 提取正文的 UTF-16 码元上限，不能当作 UTF-8 字节数使用。 */
export const MAX_TEXT_LENGTH = 1_000_000;

/**
 * 校验下载内容是否仍是 Java 授权并固定的那个文件版本。
 * 空文件或超过 50 MB 先拒绝；再计算 SHA-256，并与 64 位十六进制预期值比较。
 * 预期摘要允许大写，但返回值统一为小写。摘要一致只证明字节一致，不代替访问权限检查。
 *
 * @param bytes 下载得到的完整字节，必须在调用格式解析器之前检查。
 * @param expectedSha256 Java 内部接口提供的不可变原件摘要。
 * @returns 本次字节实际计算出的 SHA-256；长度、摘要格式或匹配失败均抛错。
 */
export function verifySource(bytes: Buffer, expectedSha256: string): string {
  if (bytes.length === 0 || bytes.length > MAX_SOURCE_BYTES) {
    throw new Error('文件为空或超过解析上限');
  }
  const sourceSha256 = createHash('sha256').update(bytes).digest('hex');
  if (!/^[a-fA-F0-9]{64}$/.test(expectedSha256) || sourceSha256 !== expectedSha256.toLowerCase()) {
    throw new Error('文件摘要校验失败');
  }
  return sourceSha256;
}

/**
 * 统一拒绝空白正文和超过 100 万 UTF-16 码元的提取结果。
 * trim 只用于判断是否为空，不修改返回正文；调用方需保留它作为后续引用定位基准。
 *
 * @param text TXT 解码或 DOCX/PDF 提取得到的正文。
 * @param format 用于拼接本地错误提示的格式名称，不参与正文转换。
 * @returns 传入的原字符串，校验失败时抛错。
 */
export function requireExtractedText(text: string, format: string): string {
  if (!text.trim()) {
    throw new Error(`${format}没有可提取的正文`);
  }
  if (text.length > MAX_TEXT_LENGTH) {
    throw new Error('解析正文超过 100 万字符上限');
  }
  return text;
}
