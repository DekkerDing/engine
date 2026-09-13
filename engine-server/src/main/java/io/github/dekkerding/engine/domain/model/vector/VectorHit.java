package io.github.dekkerding.engine.domain.model.vector;

/**
 * 向量检索命中 —— 检索结果条目（entry 元数据 + 相似度分数）。
 */
public class VectorHit {

    private final VectorEntry entry;
    private final double score;

    public VectorHit(VectorEntry entry, double score) {
        this.entry = entry;
        this.score = score;
    }

    public VectorEntry getEntry() { return entry; }
    /** 相似度分数：归一化向量点积 ∈ [-1, 1]，越大越相似 */
    public double getScore() { return score; }
}
