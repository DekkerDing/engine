package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.model.search.TextHit;

import java.util.List;

/**
 * 全文索引端口 —— 向量检索的"字面量兜底"通道。
 *
 * <p>【教学注释 · 为什么向量之外还要全文索引】
 * 语义向量擅长"意思相近"（"宝塔" ≈ "红塔"），但对低频专有名词、编号、精确字面
 * （"红塔"两个字原样出现）反而可能漂移。BM25 全文检索恰好相反：字面命中强、
 * 语义泛化弱。两路互补，RRF 融合后的召回比任何单路都稳（design.md D3）。
 *
 * <p>【图片扩展预留】本端口只约定文本块；后期图片的"描述文本"（caption/OCR）
 * 复用同一端口入索引，模态过滤在上层做——接口签名不含模态语义，天然可扩展。
 */
public interface FullTextIndex {

    /** 写入/覆盖一个文档的全部块（同 documentId 先清后写，幂等——与 VectorStore.save 口径一致） */
    void indexDocument(String documentId, List<TextChunk> chunks);

    /** 删除一个文档的全部索引块（文档删除时联动清理） */
    void deleteDocument(String documentId);

    /** 关键词检索 TopK（BM25 打分，含高亮片段） */
    List<TextHit> search(String query, int topK);
}
