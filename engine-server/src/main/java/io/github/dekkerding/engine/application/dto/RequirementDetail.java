package io.github.dekkerding.engine.application.dto;

import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;

import java.util.Collections;
import java.util.List;

/**
 * 需求详情（应用层 DTO）—— 聚合根 + 附件清单 + 工件索引的一次性组装结果。
 *
 * <p>【教学注释 · 为什么需要组装 DTO】跨三个仓储的数据在用例边界拼好，
 * interfaces 层只做协议转换不再碰仓储——避免"控制器里查库"的越层。
 */
public class RequirementDetail {

    private final RequirementSubmission submission;
    private final List<Attachment> attachments;
    private final List<MdArtifact> artifacts;

    public RequirementDetail(RequirementSubmission submission, List<Attachment> attachments,
                             List<MdArtifact> artifacts) {
        this.submission = submission;
        this.attachments = Collections.unmodifiableList(attachments);
        this.artifacts = Collections.unmodifiableList(artifacts);
    }

    public RequirementSubmission getSubmission() { return submission; }
    public List<Attachment> getAttachments() { return attachments; }
    public List<MdArtifact> getArtifacts() { return artifacts; }
}
