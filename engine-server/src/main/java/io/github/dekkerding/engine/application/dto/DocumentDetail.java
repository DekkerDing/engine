package io.github.dekkerding.engine.application.dto;

import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.TextChunk;

import java.util.List;

/**
 * 文档详情 DTO —— 应用层聚合的"一次查询要的全部信息"。
 *
 * <p>【教学注释 · 为什么需要 DTO 而不直接返回 Document】
 * 详情页要同时展示：文档元数据（Document）+ 分块列表（TextChunk）+ 向量化元信息
 * （模型/维度/降级原因，来自 EmbeddingProvider）。三个来源分属不同端口，
 * 让接口层自己拼三次数一次组装是泄漏编排职责——编排正是应用层的天职。
 */
public class DocumentDetail {

    private final Document document;
    private final List<TextChunk> chunks;
    private final String modelKey;
    private final int dimension;
    private final String degradedReason;

    public DocumentDetail(Document document, List<TextChunk> chunks,
                          String modelKey, int dimension, String degradedReason) {
        this.document = document;
        this.chunks = chunks;
        this.modelKey = modelKey;
        this.dimension = dimension;
        this.degradedReason = degradedReason;
    }

    public Document getDocument() { return document; }
    public List<TextChunk> getChunks() { return chunks; }
    public String getModelKey() { return modelKey; }
    public int getDimension() { return dimension; }
    /** 引擎降级时的原因（如"真实模型加载失败，使用哈希兜底"）；未降级为 null */
    public String getDegradedReason() { return degradedReason; }
}
