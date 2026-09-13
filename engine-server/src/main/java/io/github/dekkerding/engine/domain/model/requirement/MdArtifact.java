package io.github.dekkerding.engine.domain.model.requirement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * md 工件实体 —— 一次渲染的产物（一个需求 × 一个目标 × 一个版本）。
 *
 * <p>【三份内容域】（design D2 的三列语义）：
 * <ul>
 *   <li>templateOutput —— 模板原始输出，不可变。确定性断言的基准（同 IR + 同模板版本 ⇒ 与它字节相同）</li>
 *   <li>revised —— 人工修订，可空，整体覆盖不合并</li>
 *   <li>files —— 当前有效内容（渲染时 = templateOutput；保存修订时 = revised）。读侧/导出只看它</li>
 * </ul>
 *
 * <p>多文件产物用有序 Map（openspec 包 proposal/tasks/specs；vibecoding 单文件 TASK.md），
 * LinkedHashMap 保证 zip 内文件顺序稳定（同输入同输出的确定性纪律延伸到导出）。
 */
public class MdArtifact {

    private final long id;
    private final String requirementId;
    private final RenderTarget target;
    private final int version;
    private final Map<String, String> templateOutput;
    private final String templateVersion;
    private final Instant createdAt;
    private Map<String, String> revised;
    private Map<String, String> files;
    private boolean stale;

    public MdArtifact(long id, String requirementId, RenderTarget target, int version,
                      Map<String, String> templateOutput, String templateVersion, Instant createdAt) {
        this.id = id;
        this.requirementId = requirementId;
        this.target = target;
        this.version = version;
        this.templateOutput = templateOutput;
        this.templateVersion = templateVersion;
        this.createdAt = createdAt;
        this.files = new LinkedHashMap<String, String>(templateOutput);
        this.revised = null;
        this.stale = false;
    }

    /** 渲染即新建版本：过期标记天然为 false（新产物基于最新 IR） */
    public boolean isStale() { return stale; }

    /** 需求编辑联动：需求任何 IR 变化 ⇒ 全部工件过期（应用层调用） */
    public void markStale() {
        this.stale = true;
    }

    /** 保存人工修订：整体覆盖（不与模板输出合并），files 随之切换为修订内容 */
    public void applyRevision(Map<String, String> newRevised) {
        this.revised = new LinkedHashMap<String, String>(newRevised);
        this.files = new LinkedHashMap<String, String>(newRevised);
    }

    /** 仓储重建（同聚合根 restore 边界说明：库里的状态是历史事实） */
    public void restoreRevisionState(Map<String, String> revised, Map<String, String> files, boolean stale) {
        this.revised = revised;
        this.files = files;
        this.stale = stale;
    }

    public long getId() { return id; }
    public String getRequirementId() { return requirementId; }
    public RenderTarget getTarget() { return target; }
    public int getVersion() { return version; }
    public Map<String, String> getTemplateOutput() { return templateOutput; }
    public Map<String, String> getRevised() { return revised; }
    public Map<String, String> getFiles() { return files; }
    public String getTemplateVersion() { return templateVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
