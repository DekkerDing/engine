package io.github.dekkerding.engine.domain.model.image;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 图片资产实体（聚合根）—— 用户上传的一张照片。
 *
 * <p>【与 Document 的对称性】同样的身份（id）/生命周期（状态机）/降级标记/
 * 原件可追溯（storedPath）；差异在从属数据：文档名下是"多块多向量"，
 * 图片名下是"恰一条向量"（spec：一图一向量）——所以没有 chunkCount/progress 字段，
 * 状态从 VECTORIZING 一步到 COMPLETED。
 *
 * <p>【语义标注字段】subject/description/tags 是图片的"语义身份"
 * （photo-semantic-search 变更）：annotationMocked 用 Boolean 三态表达
 * null=未拆分（存量图/标注失败）、true=mock 产出、false=真实 VLM 产出
 * ——三态必须可区分（spec：mocked=true 显式透传，未拆分≠mocked）。
 */
public class ImageAsset {

    private final String id;
    private String filename;
    private final String storedPath;
    private final long fileSizeBytes;
    private ImageStatus status;
    private boolean degraded;
    private String errorMessage;
    private final Instant createdAt;
    private Instant updatedAt;

    // ---- 语义标注（V7 列；默认态 = 未拆分） ----
    private String subject = "";
    private String description = "";
    private List<String> tags = Collections.emptyList();
    private Boolean annotationMocked = null;
    private String annotationError;

    public ImageAsset(String id, String filename, String storedPath, long fileSizeBytes, Instant createdAt) {
        this.id = id;
        this.filename = filename;
        this.storedPath = storedPath;
        this.fileSizeBytes = fileSizeBytes;
        this.status = ImageStatus.PENDING;
        this.degraded = false;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 状态流转（带状态机校验）：非法流转抛异常，调用方必须处理 */
    public void transitionTo(ImageStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "非法状态流转: " + status + " → " + next + "（图片 " + id + "）");
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /** 处理失败：记录原因并进入终态（任何状态都可进入 FAILED，不阻塞其他图片） */
    public void markFailed(String reason) {
        this.status = ImageStatus.FAILED;
        this.errorMessage = reason;
        this.updatedAt = Instant.now();
    }

    /** 降级标记：CLIP 返回哈希兜底向量时置位（spec：无标志的假向量是禁止项） */
    public void markDegraded() {
        this.degraded = true;
    }

    /** 应用语义标注（成功路径）：清空失败原因，落三字段与 mocked 标志 */
    public void applyAnnotation(ImageAnnotation annotation) {
        this.subject = annotation.getSubject();
        this.description = annotation.getDescription();
        this.tags = annotation.getTags();
        this.annotationMocked = annotation.isMocked();
        this.annotationError = null;
        this.updatedAt = Instant.now();
    }

    /** 标注失败：字段保持"未拆分"态并记录原因（spec：标注失败不阻塞入库） */
    public void markAnnotationFailed(String reason) {
        this.subject = "";
        this.description = "";
        this.tags = Collections.emptyList();
        this.annotationMocked = null;
        this.annotationError = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * 描述路向量化失败：标注字段保留（spec：任一路失败保持另一路可用），
     * 原因记入 annotationError 供详情页追溯——不进入 FAILED（像素路向量仍可检索）。
     */
    public void markDescriptionVectorizationFailed(String reason) {
        this.annotationError = reason;
        this.updatedAt = Instant.now();
    }

    /** 标注文本的拼接形态（描述路向量化与重排取文本的统一来源） */
    public String annotationText() {
        if (subject == null || subject.isEmpty()) {
            return description == null ? "" : description;
        }
        return (description == null || description.isEmpty()) ? subject : subject + "。" + description;
    }

    /** 是否已具备标注内容（未拆分/失败均返回 false） */
    public boolean isAnnotated() {
        return (subject != null && !subject.isEmpty()) || (description != null && !description.isEmpty());
    }

    /**
     * 从持久层重建对象时直接置位（绕过状态机校验）。
     * 【边界说明】只供仓储实现调用——库里的状态是历史事实；
     * 业务代码的流转必须走 transitionTo/markFailed。
     */
    public void restoreState(ImageStatus status, boolean degraded, String errorMessage) {
        this.status = status;
        this.degraded = degraded;
        this.errorMessage = errorMessage;
    }

    /** 持久层重建标注字段（同 restoreState 的边界说明：仅供仓储调用） */
    public void restoreAnnotation(String subject, String description, List<String> tags,
                                  Boolean annotationMocked, String annotationError) {
        this.subject = subject == null ? "" : subject;
        this.description = description == null ? "" : description;
        this.tags = tags == null ? Collections.<String>emptyList() : Collections.unmodifiableList(tags);
        this.annotationMocked = annotationMocked;
        this.annotationError = annotationError;
    }

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getStoredPath() { return storedPath; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public ImageStatus getStatus() { return status; }
    public boolean isDegraded() { return degraded; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public List<String> getTags() { return tags; }
    /** null=未拆分；true=mock 产出；false=真实 VLM 产出 */
    public Boolean getAnnotationMocked() { return annotationMocked; }
    public String getAnnotationError() { return annotationError; }
}

