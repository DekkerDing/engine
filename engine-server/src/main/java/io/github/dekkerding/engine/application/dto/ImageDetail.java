package io.github.dekkerding.engine.application.dto;

import io.github.dekkerding.engine.domain.model.image.ImageAsset;

/**
 * 图片详情 DTO —— 与 {@link DocumentDetail} 对称的应用层聚合。
 *
 * <p>图片详情页要同时展示：资产元数据（ImageAsset）+ 向量化元信息
 * （模型/维度/降级原因，来自 CLIP provider 与 vision 状态端口）。
 * 图片没有分块列表——"一图一向量"没有可枚举的中间产物。
 */
public class ImageDetail {

    private final ImageAsset imageAsset;
    private final String modelKey;
    private final int dimension;
    private final String degradedReason;

    public ImageDetail(ImageAsset imageAsset, String modelKey, int dimension, String degradedReason) {
        this.imageAsset = imageAsset;
        this.modelKey = modelKey;
        this.dimension = dimension;
        this.degradedReason = degradedReason;
    }

    public ImageAsset getImageAsset() { return imageAsset; }
    public String getModelKey() { return modelKey; }
    public int getDimension() { return dimension; }
    /** CLIP 引擎降级时的原因；未降级为 null（前端黄条提示用） */
    public String getDegradedReason() { return degradedReason; }
}
