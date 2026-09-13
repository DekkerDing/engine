package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;

import java.util.List;

/**
 * 向量库端口 —— 本期 SqliteVectorStore（暴力余弦）实现；
 * 后期可平替为 FaissVectorStore（ANN 索引）而应用层零改动。
 *
 * <p>【图片扩展预留】search 的 sourceType 参数：null=全库混检（CLIP 跨模态），
 * "text"/"image"=按模态过滤——接口天然支持图片检索，不用改签名。
 */
public interface VectorStore {

    /** 批量写入一个文档的全部向量条目（同 documentId 先清后写，保证幂等） */
    void save(String documentId, List<VectorEntry> entries);

    /**
     * 按查询向量检索 TopK。sourceType / modelKey 为 null 时该维度不过滤。
     *
     * <p>【空间闸门】(sourceType, modelKey) 联合标识一个向量空间——跨空间向量不可比
     * （即使维度恰好相同，如 512 维文本向量 vs 512 维 CLIP 向量），目标空间无候选时
     * 实现必须抛明确错误而非返回空列表。modelKey 与 Python registry 的注册键一致。
     */
    List<VectorHit> search(float[] queryVector, int topK, String sourceType, String modelKey);

    void deleteByDocument(String documentId);

    /** 库内条目总数（System 页展示） */
    long count();
}
