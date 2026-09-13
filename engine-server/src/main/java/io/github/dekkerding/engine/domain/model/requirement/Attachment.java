package io.github.dekkerding.engine.domain.model.requirement;

import java.time.Instant;

/**
 * 附件实体 —— 需求的附属资源（截图/原型/参考文档）。
 *
 * <p>格式白名单 png/jpg/jpeg/pdf/docx + 魔数校验 + 20MB 上限
 * （AttachmentFormatGuard，沿 ImageFormatGuard 模式）。
 */
public class Attachment {

    private final long id;
    private final String requirementId;
    private final String fileName;
    private final String storedPath;
    private final long sizeBytes;
    private final String contentType;
    private final Instant createdAt;

    public Attachment(long id, String requirementId, String fileName, String storedPath,
                      long sizeBytes, String contentType, Instant createdAt) {
        this.id = id;
        this.requirementId = requirementId;
        this.fileName = fileName;
        this.storedPath = storedPath;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getRequirementId() { return requirementId; }
    public String getFileName() { return fileName; }
    public String getStoredPath() { return storedPath; }
    public long getSizeBytes() { return sizeBytes; }
    public String getContentType() { return contentType; }
    public Instant getCreatedAt() { return createdAt; }
}
