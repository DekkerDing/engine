package io.github.dekkerding.engine.domain.service;

/**
 * 向量化领域服务 —— 相似度数学的纯函数集合（无状态、无 IO、可独立单测）。
 *
 * <p>【教学注释 · 余弦相似度】
 * cos(A,B) = A·B / (|A|×|B|)。向量都做过 L2 归一化后 |A|=|B|=1，
 * 点积即余弦——所以 {@link #dot} 就是我们的相似度函数（省一次除法，万级块×384维时可观）。
 * 取值 [-1,1]：1 同向（语义相同）、0 无关、-1 反向。
 */
public final class VectorizationDomainService {

    private VectorizationDomainService() {
    }

    /** 点积（归一化向量下 = 余弦相似度） */
    public static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i]; // double 累加：384 项 float 累加会有精度损失
        }
        return sum;
    }

    /** 通用余弦（向量未归一化时使用；自己归一化的数据走 dot 更快） */
    public static double cosine(float[] a, float[] b) {
        return dot(a, b) / (norm(a) * norm(b) + 1e-12);
    }

    /** 向量模长 */
    public static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }
}
