package io.github.dekkerding.engine.domain.model.requirement;

import java.time.Instant;

/**
 * 需求聚合根 —— 业务人员提交的一条需求（表单 + 状态 + 生命周期时间戳）。
 *
 * <p>【教学注释 · 聚合边界】md 工件与附件都挂在需求名下：编辑需求（form 变化）
 * 必须联动"全部工件过期"——这条不变量由应用服务在同一个用例里完成，
 * 保证不会出现"表单改了、工件还是新"的脏状态。
 *
 * <p>title/capabilitySlug 同时冗余为列（列表查询不解析 form_data_json）。
 */
public class RequirementSubmission {

    private final String id;
    private String title;
    private String capabilitySlug;
    private RequirementStatus status;
    private RequirementForm form;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant exportedAt;

    public RequirementSubmission(String id, RequirementForm form, Instant createdAt) {
        this.id = id;
        this.form = form;
        this.status = RequirementStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        syncDenormalizedFields();
    }

    /** 列/字段同步：title 与 slug 冗余列跟随表单第一步的值（编辑后立即刷新） */
    private void syncDenormalizedFields() {
        if (form != null && form.getBasic() != null) {
            this.title = safe(form.getBasic().getTitle());
            String slug = safe(form.getBasic().getCapabilitySlug());
            this.capabilitySlug = slug.isEmpty() ? "req-" + id.substring(0, 8) : slug;
        } else {
            this.title = "";
            this.capabilitySlug = "req-" + id.substring(0, 8);
        }
    }

    /**
     * 保存编辑：任何状态回草稿 + 刷新冗余列 + 触摸更新时间。
     * 【边界】工件过期联动在应用层完成（聚合不持有工件集合——仓储分表管理）。
     */
    public void editForm(RequirementForm newForm) {
        this.form = newForm;
        this.status = RequirementStatus.DRAFT;
        this.updatedAt = Instant.now();
        syncDenormalizedFields();
    }

    /** 提交（闸门已由应用层校验通过）：进入 SUBMITTED 并记录时间 */
    public void markSubmitted() {
        if (!status.canTransitionTo(RequirementStatus.SUBMITTED)) {
            throw new IllegalStateException("非法状态流转: " + status + " → SUBMITTED（需求 " + id + "）");
        }
        this.status = RequirementStatus.SUBMITTED;
        this.submittedAt = Instant.now();
        this.updatedAt = this.submittedAt;
    }

    /** 导出：进入 EXPORTED 并记录时间（允许重复导出刷新时间） */
    public void markExported() {
        if (!status.canTransitionTo(RequirementStatus.EXPORTED)) {
            throw new IllegalStateException("非法状态流转: " + status + " → EXPORTED（需求 " + id + "）");
        }
        this.status = RequirementStatus.EXPORTED;
        this.exportedAt = Instant.now();
        this.updatedAt = this.exportedAt;
    }

    /** 仓储重建（绕过状态机——库里的状态是历史事实，见 Document.restoreStatus 同款边界说明） */
    public void restoreStatus(RequirementStatus status, Instant submittedAt, Instant exportedAt) {
        this.status = status;
        this.submittedAt = submittedAt;
        this.exportedAt = exportedAt;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getCapabilitySlug() { return capabilitySlug; }
    public RequirementStatus getStatus() { return status; }
    public RequirementForm getForm() { return form; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getExportedAt() { return exportedAt; }
}
