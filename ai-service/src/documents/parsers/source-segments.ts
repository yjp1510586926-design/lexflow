import { ParsedDocument } from './plain-text.parser';

/**
 * 原文区间采用 UTF-16 左闭右开区间 [startOffset, endOffset)，可直接用于 substring。
 * 这里的偏移属于 ParsedDocument.text，不是原文件字节偏移、PDF 坐标或 DOCX XML 位置。
 */
export interface SourceSegment {
  /** PDF 对应 PAGE，TXT/DOCX 对应 PARAGRAPH。 */
  kind: 'PAGE' | 'PARAGRAPH';
  /** 真实页号或解析段号，从 1 开始；页号可能不连续。 */
  index: number;
  /** 片段首个 UTF-16 码元的位置，包含该位置。 */
  startOffset: number;
  /** 片段末尾之后的位置，不包含该位置。 */
  endOffset: number;
}

/**
 * 将页/段文字重新定位到统一正文，只回传位置，避免重复传输整份原文。
 * 有 pages 时优先使用页列表，否则使用 paragraphs；空列表或超过 5000 个片段均拒绝。
 * 依次从上个片段结尾继续找原文，使同一句重复出现时不会全部指向第一次出现的位置。
 * 找不到对应文字说明解析结构与正文不一致，此时抛错，不能生成猜测位置。
 *
 * 例如“甲😀乙”的 length 为 4，emoji 占两个 UTF-16 码元，区间 [1, 3) 才是完整 emoji。
 * Java String.substring 与 JavaScript 字符串偏移使用相同计量，因此两端能核对同一引用。
 *
 * @param parsed 已完成正文提取的结果，页/段文字必须按正文顺序出现。
 * @returns 含页段类型、编号和精确偏移的列表，不重复携带片段正文。
 */
export function sourceSegments(parsed: ParsedDocument): SourceSegment[] {
  const entries = parsed.pages
    ? parsed.pages.map((page) => ({ kind: 'PAGE' as const, index: page.number, text: page.text }))
    : (parsed.paragraphs ?? []).map((paragraph) => ({
        kind: 'PARAGRAPH' as const,
        index: paragraph.index,
        text: paragraph.text,
      }));
  if (entries.length === 0 || entries.length > 5000) {
    throw new Error('文件段落数量超过 5000 个解析上限');
  }
  // cursor 只向前推进：允许正文中存在页标记或空行，但不允许已定位片段倒退或重叠。
  // 每个 entries.text 原样搜索；修改空白或标点会影响位置，因此这里不做二次清洗。
  let cursor = 0;
  return entries.map((entry) => {
    const startOffset = parsed.text.indexOf(entry.text, cursor);
    if (startOffset < 0) {
      throw new Error('来源位置与解析原文不一致');
    }
    const endOffset = startOffset + entry.text.length;
    cursor = endOffset;
    return { kind: entry.kind, index: entry.index, startOffset, endOffset };
  });
}
