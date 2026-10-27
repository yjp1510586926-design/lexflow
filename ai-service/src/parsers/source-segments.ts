import { ParsedDocument } from './plain-text.parser';

export interface SourceSegment {
  kind: 'PAGE' | 'PARAGRAPH';
  index: number;
  startOffset: number;
  endOffset: number;
}

/** UTF-16 字符偏移与 Java String.substring 一致，避免重复传输原文。 */
export function sourceSegments(parsed: ParsedDocument): SourceSegment[] {
  const entries = parsed.pages
    ? parsed.pages.map((page) => ({ kind: 'PAGE' as const, index: page.number, text: page.text }))
    : (parsed.paragraphs ?? []).map((paragraph) => ({
        kind: 'PARAGRAPH' as const,
        index: paragraph.index,
        text: paragraph.text,
      }));
  if (entries.length === 0 || entries.length > 5000)
    throw new Error('文件段落数量超过 5000 个解析上限');
  let cursor = 0;
  return entries.map((entry) => {
    const startOffset = parsed.text.indexOf(entry.text, cursor);
    if (startOffset < 0) throw new Error('来源位置与解析原文不一致');
    const endOffset = startOffset + entry.text.length;
    cursor = endOffset;
    return { kind: entry.kind, index: entry.index, startOffset, endOffset };
  });
}
