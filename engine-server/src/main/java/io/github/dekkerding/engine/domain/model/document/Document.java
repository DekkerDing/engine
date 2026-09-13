package io.github.dekkerding.engine.domain.model.document;

import java.time.Instant;

/**
 * 文档实体（聚合根）—— 用户上传的一份 txt/pdf/docx。
 *
 * <p>【教学注释 · 实体 vs 值对象】Document 有身份（id）、有生命周期（状态机），
 * 是 DDD 里的实体+聚合根：分块、向量都挂在它名下，删除文档应级联清理它们。
 *
 * <p>【教学注释 · 为什么 domain 模型不碰 JPA/MyBatis 注解】
 * 领域模型保持"纯 Java"（POJO），持久化映射是 infrastructure 的事——
 * 这让领域逻辑可以脱离数据库单测（new Document(...) 就能跑）。
 */
public class Document {

    private final String id;
    private String filename;
    private String storedPath;
    private final long fileSizeBytes;
    private DocumentStatus status;
    private int chunkCount;
    private int vectorizedCount;
    private boolean degraded;
    private String errorMessage;
    private final Instant createdAt;
    private Instant updatedAt;

    public Document(String id, String filename, String storedPath, long fileSizeBytes, Instant createdAt) {
        this.id = id;
        this.filename = filename;
        this.storedPath = storedPath;
        this.fileSizeBytes = fileSizeBytes;
        this.status = DocumentStatus.PENDING;
        this.chunkCount = 0;
        this.vectorizedCount = 0;
        this.degraded = false;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 状态流转（带状态机校验）：非法流转抛异常，调用方必须处理 */
    public void transitionTo(DocumentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "非法状态流转: " + status + " → " + next + "（文档 " + id + "）");
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /** 处理失败：记录原因并进入终态（任何状态都可进入 FAILED） */
    public void markFailed(String reason) {
        this.status = DocumentStatus.FAILED;
        this.errorMessage = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * 从持久层重建对象时直接置位（绕过状态机校验）。
     * 【边界说明】只供仓储实现调用——库里的状态是历史事实，不需要重新校验合法性；
     * 业务代码的流转必须走 transitionTo/markFailed。
     */
    public void restoreStatus(DocumentStatus status, String errorMessage) {
        this.status = status;
        this.errorMessage = errorMessage;
    }

    /** 分块完成后记录分块数 */
    public void recordChunks(int count) {
        this.chunkCount = count;
        this.updatedAt = Instant.now();
    }

    /** 向量化进度（每批完成推进一次：前端轮询据此画进度条） */
    public void recordVectorized(int count) {
        this.vectorizedCount = count;
        this.updatedAt = Instant.now();
    }

    /** 降级标记：引擎返回哈希兜底向量时置位（spec：文档与块都要显式标记） */
    public void markDegraded() {
        this.degraded = true;
    }

    /** 仓储重建进度字段（同 restoreStatus 的边界说明） */
    public void restoreProgress(int vectorizedCount, boolean degraded) {
        this.vectorizedCount = vectorizedCount;
        this.degraded = degraded;
    }

    // ---------- getter / setter（setter 只开放确实会变化的字段） ----------

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getStoredPath() { return storedPath; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public DocumentStatus getStatus() { return status; }
    public int getChunkCount() { return chunkCount; }
    public int getVectorizedCount() { return vectorizedCount; }
    public boolean isDegraded() { return degraded; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
