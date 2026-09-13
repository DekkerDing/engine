package io.github.dekkerding.engine.interfaces.rest.dto;

import io.github.dekkerding.engine.domain.model.image.ImageAsset;

import java.util.List;

/**
 * 图片列表项 DTO —— 与 DocumentSummary 对称的防腐层
 * （不泄漏 storedPath；机器值 + 中文标签一体）。
 *
 * <p>与文档的差异：没有 chunkCount/vectorizedCount（一图一向量，
 * 无进度可言）——DTO 字段忠实反映领域差异，而不是硬凑对称。
 *
 * <p>【语义标注透出 · photo-semantic-search】subject/description/tags +
 * annotationMocked 三态（null=未拆分 / true=mock 产出 / false=真实 VLM 产出）
 * ——前端据此渲染"未拆分"占位与 mocked 徽标（spec：mocked 必须显式透传，
 * 未拆分 ≠ mock）。annotationError 供详情页追溯标注/描述路失败原因。
 */
public class ImageSummary {

    private final String id;
    private final String filename;
    private final String status;       // 机器值：PENDING/VECTORIZING/COMPLETED/FAILED
    private final String statusLabel;  // 中文标签：直接展示
    private final long sizeBytes;
    private final boolean degraded;
    private final String errorMessage;
    private final String createdAt;
    private final String updatedAt;

    // ---- 语义标注（存量图 = 空串/空表/NULL 的"未拆分"占位语义） ----
    private final String subject;
    private final String description;
    private final List<String> tags;
    private final Boolean annotationMocked; // 三态：null=未拆分
    private final String annotationError;

    public ImageSummary(ImageAsset imageAsset) {
        this.id = imageAsset.getId();
        this.filename = imageAsset.getFilename();
        this.status = imageAsset.getStatus().name();
        this.statusLabel = imageAsset.getStatus().getLabel();
        this.sizeBytes = imageAsset.getFileSizeBytes();
        this.degraded = imageAsset.isDegraded();
        this.errorMessage = imageAsset.getErrorMessage();
        this.createdAt = imageAsset.getCreatedAt().toString();
        this.updatedAt = imageAsset.getUpdatedAt().toString();
        this.subject = imageAsset.getSubject();
        this.description = imageAsset.getDescription();
        this.tags = imageAsset.getTags();
        this.annotationMocked = imageAsset.getAnnotationMocked();
        this.annotationError = imageAsset.getAnnotationError();
    }

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getStatus() { return status; }
    public String getStatusLabel() { return statusLabel; }
    public long getSizeBytes() { return sizeBytes; }
    public boolean isDegraded() { return degraded; }
    public String getErrorMessage() { return errorMessage; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public List<String> getTags() { return tags; }
    public Boolean getAnnotationMocked() { return annotationMocked; }
    public String getAnnotationError() { return annotationError; }
}
