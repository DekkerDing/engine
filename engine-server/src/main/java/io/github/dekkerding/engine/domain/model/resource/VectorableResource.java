package io.github.dekkerding.engine.domain.model.resource;

/**
 * 可向量化资源 —— 整个"多模态检索"架构的扩展锚点。
 *
 * <p>【设计意图】检索的本质是"任何东西 → 向量 → 相似度"。把这个"任何东西"
 * 抽象成接口后，向量库、检索、流水线全部与具体模态解耦：
 * <pre>
 * 本期：TextDocumentResource（文档分块） —— 文本搜文本
 * 后期：ImageAssetResource（照片库）     —— 图片搜图片
 * 后期：CLIP 跨模态                      —— "红塔+小花"文字直接搜照片
 * </pre>
 *
 * <p>后期接入图片时：新增一个实现类 + 一个 EmbeddingProvider(IMAGE)，domain 其余代码零改动。
 */
public interface VectorableResource {

    /** 资源唯一标识（写入向量库，检索结果可反查来源） */
    String resourceId();

    /** 来源类型标签：text / image —— 向量库按它过滤，前端按它渲染不同卡片 */
    String sourceType();

    /**
     * 资源的文本表示。
     * TEXT 模态就是原文；IMAGE 模态后期是图片（CLIP 直接吃像素，此方法返回图片路径/描述）。
     */
    String asText();
}
