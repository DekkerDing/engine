package io.github.dekkerding.engine.domain.model.vector;

/**
 * 向量值对象 —— 一次编码的结果。
 *
 * <p>【教学注释 · 为什么 float[] 而不是 List&lt;Float&gt;】
 * 384 维向量是数值计算负载：float[] 连续内存、直接喂余弦运算；
 * List&lt;Float&gt; 每个元素都是包装对象（装箱），内存翻 4 倍、缓存不友好。
 * 数值密集场景永远用原始类型数组——这是 Java 性能常识第一条。
 */
public class Embedding {

    private final float[] vector;
    private final String modelKey;
    private final int dimension;
    private final boolean degraded;

    public Embedding(float[] vector, String modelKey, boolean degraded) {
        this.vector = vector;
        this.modelKey = modelKey;
        this.dimension = vector.length;
        this.degraded = degraded;
    }

    public float[] getVector() { return vector; }
    public String getModelKey() { return modelKey; }
    public int getDimension() { return dimension; }
    /** 降级标志：true = 哈希兜底向量，语义精度受限（前端黄条） */
    public boolean isDegraded() { return degraded; }
}
