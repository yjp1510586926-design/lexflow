import { requireExtractedText, verifySource } from './source-validation';

/**
 * 所有文件格式共用的解析结果，消费者将它回传给 Java 保存。
 * 后续模型引用只能从这份 text 中取原文；pages/paragraphs 用于生成可核验的来源区间。
 * 该结构不含摘要生成、法律结论或对原始文件的访问授权。
 */
export interface ParsedDocument {
  /** 解析结果协议版本，与软件包版本无关。 */
  schemaVersion: '1';
  /** 实际采用的解析格式，供接收端识别正文结构。 */
  mimeType: string;
  /** 原始文件字节的摘要，用来绑定 Java 授权的不可变文件版本。 */
  sourceSha256: string;
  /** 定位偏移的唯一基准：TXT 保留解码正文，DOCX/PDF 使用各自提取后的统一正文。 */
  text: string;
  /** TXT/DOCX 的非空段列表，index 从 1 开始；不代表打印页码。 */
  paragraphs?: { index: number; text: string }[];
  /** PDF 的非空文字页，number 使用真实页号，可能因空白页被跳过而不连续。 */
  pages?: { number: number; text: string }[];
}

/**
 * 解析 UTF-8 TXT：校验字节摘要 → 严格解码 → 检查控制字符和正文上限 → 划分段落。
 * UTF-8 损坏时直接报错，不用替换字符悄悄改变原文；制表、换行和回车不在拒绝范围内。
 * 正文不会整体 trim，段落按空行分隔，过滤纯空白段后从 1 编号。
 *
 * @param bytes 已授权版本的文件字节，长度必须在 1 字节至 50 MB 之间。
 * @param mimeType 此入口仅接受 text/plain，其他格式应走 parseDocument。
 * @param expectedSha256 Java 提供的文件摘要；格式错误或内容不匹配均抛错。
 * @returns 保留正文和段号的解析结果；不生成不存在的页码，也不调用模型。
 */
export function parsePlainText(
  bytes: Buffer,
  mimeType: string,
  expectedSha256: string,
): ParsedDocument {
  if (mimeType !== 'text/plain') {
    throw new Error('该格式尚未接入解析器');
  }
  const sourceSha256 = verifySource(bytes, expectedSha256);
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new Error('文件不是有效的 UTF-8 文本');
  }
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/u.test(text)) {
    throw new Error('文件包含二进制控制字符');
  }
  requireExtractedText(text, 'TXT 文件');
  // 例如“甲方陈述\n\n乙方陈述”形成两个段落，单个换行仍留在同一段内。
  // 保留各段的原始字符，sourceSegments 才能把段号绑定回 text 的精确区间。
  const paragraphs = text
    .split(/\r?\n\s*\r?\n/u)
    .filter((part) => part.trim())
    .map((part, index) => ({ index: index + 1, text: part }));
  return { schemaVersion: '1', mimeType: 'text/plain', sourceSha256, text, paragraphs };
}
