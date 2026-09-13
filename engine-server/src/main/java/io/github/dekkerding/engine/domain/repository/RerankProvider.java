package io.github.dekkerding.engine.domain.repository;

import java.util.List;

/**
 * 检索重排端口 —— reranker 槽位在领域侧的投影。
 *
 * <p>【与向量化降级语义的关键差异】向量编码"模型不可用→降级哈希"是对的
 * （服务仍可用）；重排不行——伪造分数会把排序带向错误方向。实现
 * MUST 在不可用时抛出异常，由检索服务决定降级（reranked=false 直通），
 * 绝不返回假分数装作重排成功（对齐 python/core/rerank.py 的纪律注释）。
 */
public interface RerankProvider {

    /**
     * 对 (query × candidates) 逐对打分。
     *
     * @param query      查询文本
     * @param candidates 候选文本列表（通常是图片标注文本）
     * @return 与 candidates 等长的相关性分数（顺序一致，可比较排序）
     * @throws io.github.dekkerding.engine.domain.exception.EngineException
     *         重排器不可用 / 候选超上限 / 通道故障时显式抛出（调用方降级）
     */
    float[] rerank(String query, List<String> candidates);
}
