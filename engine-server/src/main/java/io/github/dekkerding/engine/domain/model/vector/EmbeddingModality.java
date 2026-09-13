package io.github.dekkerding.engine.domain.model.vector;

/**
 * 向量模态 —— 可向量化资源的类型维度。
 *
 * <p>【图片扩展预留】本期只有 TEXT；后期 CLIP 会加 CROSS（图文同空间）。
 * 值对象用 enum 表达"封闭集合"：新增模态是架构事件，应该让编译器检查所有分支。
 */
public enum EmbeddingModality {
    /** 纯文本（本期：文档分块） */
    TEXT,
    /** 纯图片（后期：照片库向量化） */
    IMAGE,
    /** 跨模态（后期：CLIP 文图同空间，支持"红塔+小花"文本直接搜图） */
    CROSS
}
