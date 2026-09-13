package io.github.dekkerding.engine.domain.model.search;

/**
 * 全文检索命中 —— Lucene 通道的结果条目（与向量通道的 {@link io.github.dekkerding.engine.domain.model.vector.VectorHit} 对偶）。
 *
 * <p>【教学注释 · 为什么分数是 double 且无界】BM25 分数与余弦分数是两种量纲：
 * 余弦 ∈ [-1,1]，BM25 依词频/文档长度可以到几十。这正是检索融合（RRF）只看排名
 * 不看原始分数的原因——两个分布不可比，见 design.md D3。
 */
public class TextHit {

    private final String documentId;
    private final int chunkIndex;
    private final String text;
    private final double score;
    /** 带高亮标记的片段（如 "公园的&lt;em&gt;红塔&lt;/em&gt;下面"）；无合适片段时为 null，由展示层截断兜底 */
    private final String highlight;

    public TextHit(String documentId, int chunkIndex, String text, double score, String highlight) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.score = score;
        this.highlight = highlight;
    }

    public String getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getText() { return text; }
    public double getScore() { return score; }
    public String getHighlight() { return highlight; }
}
