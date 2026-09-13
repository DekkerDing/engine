package io.github.dekkerding.engine.domain.model.requirement;

import java.util.ArrayList;
import java.util.List;

/**
 * RequirementIR —— 需求中间表示（编译流水线的"字节码"）。
 *
 * <p>【教学注释 · 为什么不直接表单→md】表单是"人怎么填"，IR 是"渲染器需要什么"：
 * 中间加一层 IR，①一套表单可以渲染多种目标格式；②完整性闸门作用在 IR 上
 * （与渲染目标无关）；③IR 由表单纯函数派生，不落库、永远与表单一致。
 *
 * <p>由聚合根 + 附件清单组装；渲染器只读 IR，不碰聚合与表单。
 */
public class RequirementIR {

    private final Meta meta;
    private final RequirementForm.Background background;
    private final List<RequirementForm.Feature> features;
    private final List<RequirementForm.AcceptanceCriterion> acceptance;
    private final RequirementForm.Constraints constraints;
    private final List<AttachmentRef> attachments;

    public RequirementIR(Meta meta, RequirementForm.Background background,
                         List<RequirementForm.Feature> features,
                         List<RequirementForm.AcceptanceCriterion> acceptance,
                         RequirementForm.Constraints constraints,
                         List<AttachmentRef> attachments) {
        this.meta = meta;
        this.background = background;
        this.features = features;
        this.acceptance = acceptance;
        this.constraints = constraints;
        this.attachments = attachments;
    }

    /** 工厂：聚合根 + 附件行 → IR（meta 从两者拼装） */
    public static RequirementIR of(RequirementSubmission submission, List<Attachment> attachmentRows) {
        RequirementForm form = submission.getForm();
        form.ensureDefaults();
        RequirementForm.BasicInfo basic = form.getBasic();
        Meta meta = new Meta(
                submission.getId(),
                nullToEmpty(basic.getTitle()),
                nullToEmpty(basic.getSubmitter()),
                nullToEmpty(basic.getDepartment()),
                nullToEmpty(basic.getPriority()),
                nullToEmpty(basic.getExpectDate()),
                submission.getCapabilitySlug());
        List<AttachmentRef> refs = new ArrayList<AttachmentRef>();
        for (Attachment row : attachmentRows) {
            refs.add(new AttachmentRef(row.getFileName(), row.getContentType()));
        }
        return new RequirementIR(meta, form.getBackground(), form.getFeatures(),
                form.getAcceptance(), form.getConstraints(), refs);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ---------- 组成部分 ----------

    /** 渲染所需的元信息（从聚合根冗余列 + 表单拼装） */
    public static class Meta {
        public final String id;
        public final String title;
        public final String submitter;
        public final String department;
        public final String priority;
        public final String expectDate;
        public final String capabilitySlug;

        public Meta(String id, String title, String submitter, String department,
                    String priority, String expectDate, String capabilitySlug) {
            this.id = id;
            this.title = title;
            this.submitter = submitter;
            this.department = department;
            this.priority = priority;
            this.expectDate = expectDate;
            this.capabilitySlug = capabilitySlug;
        }
    }

    /** 附件引用（渲染只用名字与类型，文件本体随导出包走） */
    public static class AttachmentRef {
        public final String name;
        public final String type;

        public AttachmentRef(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public Meta getMeta() { return meta; }
    public RequirementForm.Background getBackground() { return background; }
    public List<RequirementForm.Feature> getFeatures() { return features; }
    public List<RequirementForm.AcceptanceCriterion> getAcceptance() { return acceptance; }
    public RequirementForm.Constraints getConstraints() { return constraints; }
    public List<AttachmentRef> getAttachments() { return attachments; }
}
