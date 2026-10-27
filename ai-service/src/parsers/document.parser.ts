import { PDFParse } from 'pdf-parse';
import * as mammoth from 'mammoth';
import { ParsedDocument, parsePlainText } from './plain-text.parser';
import { requireExtractedText, verifySource } from './source-validation';

const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const MAX_PDF_PAGES = 500;

/** 从已授权的文件字节提取原文；不会调用 LLM 或生成法律结论。 */
export async function parseDocument(
  bytes: Buffer,
  mimeType: string,
  expectedSha256: string,
): Promise<ParsedDocument> {
  if (mimeType === 'text/plain') return parsePlainText(bytes, mimeType, expectedSha256);
  if (mimeType !== 'application/pdf' && mimeType !== DOCX_MIME)
    throw new Error('该格式尚未接入解析器');
  const hash = verifySource(bytes, expectedSha256);

  if (mimeType === DOCX_MIME) {
    let text: string;
    try {
      text = (await mammoth.extractRawText({ buffer: bytes })).value.trim();
    } catch {
      throw new Error('DOCX 文件无法解析');
    }
    requireExtractedText(text, 'DOCX 文件');
    const paragraphs = text
      .split(/\n\s*\n/u)
      .filter((part) => part.trim())
      .map((part, index) => ({ index: index + 1, text: part }));
    return { schemaVersion: '1', mimeType, sourceSha256: hash, text, paragraphs };
  }

  const parser = new PDFParse({ data: bytes });
  try {
    const info = await parser.getInfo();
    if (info.total > MAX_PDF_PAGES) throw new Error(`PDF 超过 ${MAX_PDF_PAGES} 页解析上限`);
    const result = await parser.getText();
    const pages = result.pages
      .map((page) => ({ number: page.num, text: page.text.trim() }))
      .filter((page) => page.text.length > 0);
    if (pages.length === 0) throw new Error('PDF 没有可提取的文字层，请上传含文字的文件');
    const text = pages.map((page) => `【第 ${page.number} 页】\n${page.text}`).join('\n\n');
    requireExtractedText(text, 'PDF 文件');
    return { schemaVersion: '1', mimeType, sourceSha256: hash, text, pages };
  } catch (error) {
    if (error instanceof Error && /解析上限|文字层|100 万字符/u.test(error.message)) throw error;
    if (error instanceof Error && error.name === 'PasswordException')
      throw new Error('PDF 已加密，请先解除密码后上传');
    throw new Error('PDF 文件无法解析');
  } finally {
    await parser.destroy().catch(() => undefined);
  }
}
