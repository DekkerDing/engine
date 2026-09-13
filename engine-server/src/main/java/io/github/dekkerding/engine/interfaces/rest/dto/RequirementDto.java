package io.github.dekkerding.engine.interfaces.rest.dto;

import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求工厂响应 DTO 集合 —— 依赖 domain 实体构造（interfaces → domain 是合法依赖方向）。
 * 每个类只做"领域对象 → 可序列化视图"的翻译，不含业务逻辑。
 */
public final class RequirementDto {

    private RequirementDto() {
    }

    /** 列表项：列表页卡片所需的最小字段集 */
    public static class RequirementSummary {
        public final String id;
        public final String title;
        public final String status;
        public final String priority;
        public final String submitter;
        public final String department;
        public final String capabilitySlug;
        public final String updatedAt;

        public RequirementSummary(RequirementSubmission s) {
            this.id = s.getId();
            this.title = s.getTitle();
            this.status = s.getStatus().name();
            this.priority = s.getForm().getBasic().getPriority();
            this.submitter = s.getForm().getBasic().getSubmitter();
            this.department = s.getForm().getBasic().getDepartment();
            this.capabilitySlug = s.getCapabilitySlug();
            this.updatedAt = s.getUpdatedAt().toString();
        }
    }

    /** 详情：表单全量 + 附件 + 工件索引 */
    public static class RequirementDetailResponse {
        public final RequirementSummary summary;
        public final Object form;
        public final List<AttachmentResponse> attachments;
        public final List<ArtifactSummary> artifacts;

        public RequirementDetailResponse(RequirementSummary summary, Object form,
                                         List<AttachmentResponse> attachments, List<ArtifactSummary> artifacts) {
            this.summary = summary;
            this.form = form;
            this.attachments = attachments;
            this.artifacts = artifacts;
        }
    }

    public static class AttachmentResponse {
        public final long id;
        public final String fileName;
        public final String contentType;
        public final long sizeBytes;
        public final String createdAt;

        public AttachmentResponse(Attachment a) {
            this.id = a.getId();
            this.fileName = a.getFileName();
            this.contentType = a.getContentType();
            this.sizeBytes = a.getSizeBytes();
            this.createdAt = a.getCreatedAt().toString();
        }
    }

    /** 工件索引项：工坊/导出页的版本列表 */
    public static class ArtifactSummary {
        public final String target;
        public final int version;
        public final boolean stale;
        public final String templateVersion;
        public final boolean hasRevision;
        public final List<String> fileNames;
        public final String createdAt;

        public ArtifactSummary(MdArtifact a) {
            this.target = a.getTarget().value();
            this.version = a.getVersion();
            this.stale = a.isStale();
            this.templateVersion = a.getTemplateVersion();
            this.hasRevision = a.getRevised() != null;
            this.fileNames = new ArrayList<String>(a.getFiles().keySet());
            this.createdAt = a.getCreatedAt().toString();
        }
    }

    /** 工件内容：files=当前有效（修订优先）；templateOutput=确定性基准（diff 视图用） */
    public static class ArtifactContentResponse {
        public final String target;
        public final int version;
        public final boolean stale;
        public final String templateVersion;
        public final java.util.Map<String, String> files;
        public final java.util.Map<String, String> templateOutput;

        public ArtifactContentResponse(MdArtifact a) {
            this.target = a.getTarget().value();
            this.version = a.getVersion();
            this.stale = a.isStale();
            this.templateVersion = a.getTemplateVersion();
            this.files = a.getFiles();
            this.templateOutput = a.getTemplateOutput();
        }
    }
}
