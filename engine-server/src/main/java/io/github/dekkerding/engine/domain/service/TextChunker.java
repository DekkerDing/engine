package io.github.dekkerding.engine.domain.service;

import io.github.dekkerding.engine.domain.model.document.TextChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器（领域服务，纯函数）—— 蓝本缺陷 g 的修复实现。
 *
 * <p>【缺陷与修复】蓝本按固定 800 字符切块，而 MiniLM 模型上限 512 token（≈400 汉字），
 * 超长块被模型**静默截断**——块的后半篇从未参与向量，检索天然漏召回。
 * 修复：目标块长 400 字符（配置 engine.documents.chunk.target-size）+ 句子边界对齐。
 *
 * <p>【算法】句子对齐滑动窗口：
 * <ol>
 *   <li>按句号类标点（。！？!?；;\n）切句</li>
 *   <li>顺序攒句子，攒到 ≥ targetSize 即成块</li>
 *   <li>下一块从「上一块最后一个句子」开始（重叠 1 句）——跨块的语义不断链</li>
 * </ol>
 *
 * <p>【教学注释 · 为什么重叠】"红塔在公园中央。塔下种满小花。" 若恰好切在两句之间，
 * 含"红塔"的块与含"小花"的块各自完整，但"红塔+小花"的关联只在重叠后同块出现的句子里
 * 才最强。重叠 1 句是最小代价的上下文保链。
 */
public class TextChunker {

    private final int targetSize;
    private final int overlapSentences;

    public TextChunker(int targetSize, int overlapSentences) {
        this.targetSize = Math.max(50, targetSize);
        this.overlapSentences = Math.max(0, overlapSentences);
    }

    public List<TextChunk> chunk(String documentId, String text) {
        List<TextChunk> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        List<String> sentences = splitSentences(text);
        int chunkIndex = 0;
        int start = 0; // 当前块的起始句子下标

        while (start < sentences.size()) {
            StringBuilder current = new StringBuilder();
            int end = start;
            while (end < sentences.size()) {
                String sentence = sentences.get(end);
                if (current.length() > 0
                        && current.length() + sentence.length() > targetSize) {
                    break; // 再加一句就超目标长度：当前块收口
                }
                current.append(sentence);
                end++;
            }
            // 兜底：单句超长（无标点的长文本）时按 targetSize 硬切，保证块不超限
            appendChunk(result, documentId, chunkIndex++, current.toString());

            if (end >= sentences.size()) {
                break;
            }
            // 下一块起点：回退 overlapSentences 句（重叠保链）
            start = Math.max(start + 1, end - overlapSentences);
        }
        return result;
    }

    /** 硬切超长单句，防止无标点长文绕过长度限制 */
    private void appendChunk(List<TextChunk> result, String documentId, int index, String text) {
        int from = 0;
        while (from < text.length()) {
            int to = Math.min(from + targetSize, text.length());
            String piece = text.substring(from, to).trim();
            if (!piece.isEmpty()) {
                result.add(new TextChunk(documentId, result.size(), piece));
            }
            from = to;
        }
    }

    /** 切句：句尾标点保留在句内；连续换行视为段落边界 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == ';' || c == '；' || c == '\n') {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }
}
