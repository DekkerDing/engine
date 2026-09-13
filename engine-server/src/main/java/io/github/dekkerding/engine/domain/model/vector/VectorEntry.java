package io.github.dekkerding.engine.domain.model.vector;

import java.time.Instant;

/**
 * 向量条目 —— 一条可被检索的向量及其元数据（向量库的一行）。
 *
 * <p>【图片扩展预留】sourceType 字段让 text/image 向量混存一张表；
 * 检索时可按 sourceType 过滤（null=全库混合检索——CLIP 跨模态检索就是混检）。
 */
public class VectorEntry {

    private final String documentId;
    private final String sourceType;   // text | image
    private final int chunkIndex;      // 文档分块序号（图片资源固定 0）
    private final String text;         // 冗余存文本：检索命中直接展示，避免 JOIN
    private final String modelKey;     // 编码模型（text-embedding / clip）
    private final int dimension;
    private final boolean degraded;    // 降级标志：true=哈希兜底向量（语义精度受限，前端黄条提示）
    private final float[] vector;
    private final Instant createdAt;

    public VectorEntry(String documentId, String sourceType, int chunkIndex, String text,
                       String modelKey, boolean degraded, float[] vector, Instant createdAt) {
        this.documentId = documentId;
        this.sourceType = sourceType;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.modelKey = modelKey;
        this.degraded = degraded;
        this.dimension = vector == null ? 0 : vector.length;
        this.vector = vector;
        this.createdAt = createdAt;
    }

    public String getDocumentId() { return documentId; }
    public String getSourceType() { return sourceType; }
    public int getChunkIndex() { return chunkIndex; }
    public String getText() { return text; }
    public String getModelKey() { return modelKey; }
    public int getDimension() { return dimension; }
    /** 降级标志随向量一起持久化：检索命中时能如实告诉用户"这条结果是哈希兜底算出来的" */
    public boolean isDegraded() { return degraded; }
    public float[] getVector() { return vector; }
    public Instant getCreatedAt() { return createdAt; }
}
