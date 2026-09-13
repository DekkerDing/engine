package io.github.dekkerding.engine.interfaces.rest.dto;

import io.github.dekkerding.engine.domain.model.document.Document;

/**
 * 文档列表项 DTO —— 领域模型到接口视图的"防腐层"。
 *
 * <p>【教学注释 · 为什么不直接返回 Document】
 * 1) Document 有 storedPath（服务器文件路径，泄漏部署结构给外部——安全问题）
 * 2) status 是枚举，前端要同时拿到机器值（COMPLETED）与中文标签（"全部完成"）
 * 3) 接口字段一旦发布就是契约：领域模型演进（加字段改语义）不该自动变成 API 变更
 */
public class DocumentSummary {

    private final String id;
    private final String filename;
    private final String status;       // 机器值：PENDING/PARSING/...（前端做徽标映射）
    private final String statusLabel;  // 中文标签：直接展示
    private final long sizeBytes;      // 文件大小（spec 文档管理：列表展示大小）
    private final int chunkCount;
    private final int vectorizedCount;
    private final boolean degraded;
    private final String errorMessage;
    private final String createdAt;
    private final String updatedAt;

    public DocumentSummary(Document document) {
        this.id = document.getId();
        this.filename = document.getFilename();
        this.status = document.getStatus().name();
        this.statusLabel = document.getStatus().getLabel();
        this.sizeBytes = document.getFileSizeBytes();
        this.chunkCount = document.getChunkCount();
        this.vectorizedCount = document.getVectorizedCount();
        this.degraded = document.isDegraded();
        this.errorMessage = document.getErrorMessage();
        this.createdAt = document.getCreatedAt().toString();
        this.updatedAt = document.getUpdatedAt().toString();
    }

    public String getId() { return id; }
    public String getFilename() { return filename; }
    public String getStatus() { return status; }
    public String getStatusLabel() { return statusLabel; }
    public long getSizeBytes() { return sizeBytes; }
    public int getChunkCount() { return chunkCount; }
    public int getVectorizedCount() { return vectorizedCount; }
    public boolean isDegraded() { return degraded; }
    public String getErrorMessage() { return errorMessage; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
}
