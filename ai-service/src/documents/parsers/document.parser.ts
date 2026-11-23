import { PDFParse } from 'pdf-parse';
import * as mammoth from 'mammoth';
import { ParsedDocument, parsePlainText } from './plain-text.parser';
import { requireExtractedText, verifySource } from './source-validation';

const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const MAX_PDF_PAGES = 500;

/**
 * 把 Java 内部接口下载的文件字节转成统一的原文结构，供解析回调和后续引用校验使用。
 *
 * TXT 委托纯文本解析器；DOCX 用 Mammoth 提取正文并按空行分段；PDF 按真实页号提取文字层。
 * 此函数验证文件摘要与解析上限，不负责判断用户有没有访问文件的权限，也不执行 OCR 或调用模型。
 * 返回的 text 是后续定位使用的基准文本，不是原始文件字节或排版坐标。
 *
 * @param bytes 已下载的完整文件内容，支持的格式均需核对授权版本的 SHA-256。
 * @param mimeType Java 提供的文件类型，只接受 TXT、DOCX、PDF。
 * @param expectedSha256 Java 固定的原件摘要，避免解析过程中套用其他文件版本。
 * @returns 正文、摘要及段落或页列表；不支持的格式、空正文、超限或损坏文件会抛错。
 */
export async function parseDocument(
  bytes: Buffer,
  mimeType: string,
  expectedSha256: string,
): Promise<ParsedDocument> {
  if (mimeType === 'text/plain') {
    return parsePlainText(bytes, mimeType, expectedSha256);
  }
  if (mimeType !== 'application/pdf' && mimeType !== DOCX_MIME) {
    throw new Error('该格式尚未接入解析器');
  }
  const sourceSha256 = verifySource(bytes, expectedSha256);

  // DOCX 提取后会去除整篇首尾空白，再对空行分段；段号从 1 开始。
  // 不把表格/段落排版当成 PDF 页码，也不为无页码格式推测页码。
  if (mimeType === DOCX_MIME) {
    let text: string;
    try {
      text = (await mammoth.extractRawText({ buffer: bytes })).value.trim();
    } catch {
      throw new Error('DOCX 文件无法解析');
    }
    requireExtractedText(text, 'DOCX 文件');
    // filter 只移除纯空白段，不对各段单独 trim，确保段落文字仍能在 text 中逐字找到。
    const paragraphs = text
      .split(/\n\s*\n/u)
      .filter((part) => part.trim())
      .map((part, index) => ({ index: index + 1, text: part }));
    return { schemaVersion: '1', mimeType, sourceSha256, text, paragraphs };
  }

  // PDFParse 自己管理底层解析资源；finally 中统一销毁。
  // 先读取页数再提取文字，避免对超过 500 页的文件继续做大规模解析。
  const parser = new PDFParse({ data: bytes });
  try {
    const info = await parser.getInfo();
    if (info.total > MAX_PDF_PAGES) {
      throw new Error(`PDF 超过 ${MAX_PDF_PAGES} 页解析上限`);
    }
    const result = await parser.getText();
    // 去除每页首尾空白并忽略空白页；页号仍沿用原文件编号，因此可能不连续。
    // 扫描图片没有可提取文字时拒绝继续，不把空结果当成可引用的证据。
    const pages = result.pages
      .map((page) => ({ number: page.num, text: page.text.trim() }))
      .filter((page) => page.text.length > 0);
    if (pages.length === 0) {
      throw new Error('PDF 没有可提取的文字层，请上传含文字的文件');
    }
    // 页标记属于统一正文的一部分，后续页内引用偏移必须基于这份 text 计算。
    const text = pages.map((page) => `【第 ${page.number} 页】\n${page.text}`).join('\n\n');
    requireExtractedText(text, 'PDF 文件');
    return { schemaVersion: '1', mimeType, sourceSha256, text, pages };
  } catch (error) {
    // 已知的业务限制保留明确提示；解析库其余异常转成本地提示，不向上暴露原始库错误。
    if (error instanceof Error && /解析上限|文字层|100 万字符/u.test(error.message)) {
      throw error;
    }
    if (error instanceof Error && error.name === 'PasswordException') {
      throw new Error('PDF 已加密，请先解除密码后上传');
    }
    throw new Error('PDF 文件无法解析');
  } finally {
    // 无论解析成功与否都释放解析器；清理失败不覆盖原来的解析结果。
    await parser.destroy().catch(() => undefined);
  }
}
