package io.github.dekkerding.engine.interfaces.rest.dto;

import io.github.dekkerding.engine.application.dto.ImageDetail;

/**
 * 图片详情响应 DTO —— 与 DocumentDetailResponse 对称
 * （元数据 + 模型/维度/降级原因；图片无分块列表）。
 */
public class ImageDetailResponse {

    private final ImageSummary image;
    private final String modelKey;
    private final int dimension;
    private final String degradedReason;

    public ImageDetailResponse(ImageDetail detail) {
        this.image = new ImageSummary(detail.getImageAsset());
        this.modelKey = detail.getModelKey();
        this.dimension = detail.getDimension();
        this.degradedReason = detail.getDegradedReason();
    }

    public ImageSummary getImage() { return image; }
    public String getModelKey() { return modelKey; }
    public int getDimension() { return dimension; }
    public String getDegradedReason() { return degradedReason; }
}
