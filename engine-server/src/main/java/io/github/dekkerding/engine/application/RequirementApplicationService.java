package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.RequirementDetail;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;
import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;
import io.github.dekkerding.engine.domain.model.requirement.RequirementStatus;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;
import io.github.dekkerding.engine.domain.repository.AttachmentRepository;
import io.github.dekkerding.engine.domain.repository.AttachmentStore;
import io.github.dekkerding.engine.domain.repository.MdArtifactRepository;
import io.github.dekkerding.engine.domain.repository.RequirementRepository;
import io.github.dekkerding.engine.domain.service.RequirementValidator;
import io.github.dekkerding.engine.domain.service.renderer.OpenSpecRenderer;
import io.github.dekkerding.engine.domain.service.renderer.SpecRenderer;
import io.github.dekkerding.engine.domain.service.renderer.VibecodingRenderer;
import io.github.dekkerding.engine.infrastructure.requirement.AttachmentFormatGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 需求工厂应用服务 —— 表单收集到 md 工件的用例编排。
 *
 * <p>核心编排不变量（spec requirement-intake「需求状态生命周期」）：
 * 编辑保存 ⇒ 状态回 DRAFT + 全部工件 markStale，两步在同一个用例内完成。
 *
 * <p>渲染器是纯 domain 服务（沿 TextChunker 惯例由本类 new 装配，零框架依赖）；
 * 新增渲染目标 = Map 里加一项 + RenderTarget 加一枚举值。
 */
@Service
public class RequirementApplicationService {

    private final RequirementRepository requirementRepository;
    private final MdArtifactRepository artifactRepository;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentStore attachmentStore;
    private final AttachmentFormatGuard formatGuard;
    private final RequirementValidator validator = new RequirementValidator();
    private final Map<RenderTarget, SpecRenderer> renderers;

    public RequirementApplicationService(RequirementRepository requirementRepository,
                                         MdArtifactRepository artifactRepository,
                                         AttachmentRepository attachmentRepository,
                                         AttachmentStore attachmentStore,
                                         AttachmentFormatGuard formatGuard) {
        this.requirementRepository = requirementRepository;
        this.artifactRepository = artifactRepository;
        this.attachmentRepository = attachmentRepository;
        this.attachmentStore = attachmentStore;
        this.formatGuard = formatGuard;
        Map<RenderTarget, SpecRenderer> registered = new LinkedHashMap<RenderTarget, SpecRenderer>();
        for (SpecRenderer renderer : Arrays.<SpecRenderer>asList(new OpenSpecRenderer(), new VibecodingRenderer())) {
            registered.put(renderer.target(), renderer);
        }
        this.renderers = registered;
    }

    // ---------- 需求生命周期 ----------

    /** 创建草稿：部分字段即可（分步表单第一步暂存就会调用） */
    public RequirementSubmission create(RequirementForm form) {
        form.ensureDefaults();
        RequirementSubmission submission =
                new RequirementSubmission(UUID.randomUUID().toString(), form, Instant.now());
        validateSlugIfPresent(form);
        requirementRepository.insert(submission);
        return submission;
    }

    /**
     * 保存编辑：任何状态回 DRAFT + 冗余列刷新 + 全部工件过期（同一用例内联动）。
     */
    public RequirementSubmission save(String id, RequirementForm form) {
        RequirementSubmission submission = load(id);
        form.ensureDefaults();
        validateSlugIfPresent(form);
        submission.editForm(form);
        requirementRepository.update(submission);
        for (MdArtifact artifact : artifactRepository.findByRequirement(id)) {
            artifact.markStale();
            artifactRepository.update(artifact);
        }
        return submission;
    }

    /** 提交：完整性闸门（422 + 逐项缺失清单）→ SUBMITTED */
    public RequirementSubmission submit(String id) {
        RequirementSubmission submission = load(id);
        List<String> missing = validator.validate(buildIR(submission));
        if (!missing.isEmpty()) {
            throw EngineException.unprocessable("需求不完整，无法提交：\n- " + join(missing, "\n- "));
        }
        submission.markSubmitted();
        requirementRepository.update(submission);
        return submission;
    }

    // ---------- 渲染与工件 ----------

    /** 渲染：SUBMITTED/EXPORTED 才可发起（DRAFT 先提交过闸门）；产新版本 */
    public MdArtifact render(String id, RenderTarget target) {
        RequirementSubmission submission = load(id);
        if (submission.getStatus() == RequirementStatus.DRAFT) {
            throw EngineException.conflict("需求尚未提交（先通过完整性闸门）：" + id);
        }
        SpecRenderer renderer = renderers.get(target);
        if (renderer == null) {
            throw EngineException.badRequest("未注册的渲染目标: " + target);
        }
        RequirementIR ir = buildIR(submission);
        Map<String, String> files = renderer.render(ir);
        int version = artifactRepository.nextVersion(id, target);
        return artifactRepository.insert(new MdArtifact(-1L, id, target, version,
                files, renderer.templateVersion(), Instant.now()));
    }

    public List<MdArtifact> listArtifacts(String id) {
        load(id);
        return artifactRepository.findByRequirement(id);
    }

    public MdArtifact getArtifact(String id, RenderTarget target, int version) {
        return artifactRepository.find(id, target, version)
                .orElseThrow(() -> EngineException.notFound(
                        "工件不存在: 需求 " + id + " / " + target.value() + " v" + version));
    }

    /** 保存人工修订：整体覆盖；过期工件拒绝（提示先重新渲染） */
    public MdArtifact saveRevision(String id, RenderTarget target, int version, Map<String, String> revised) {
        load(id);
        MdArtifact artifact = getArtifact(id, target, version);
        if (artifact.isStale()) {
            throw EngineException.conflict("该工件已过期（需求已编辑），请先重新渲染再修订");
        }
        if (revised == null || revised.isEmpty()) {
            throw EngineException.badRequest("修订内容不能为空");
        }
        artifact.applyRevision(revised);
        artifactRepository.update(artifact);
        return artifact;
    }

    // ---------- 导出 ----------

    /** 导出载荷：文件映射 + 附件字节（zip 组装在 interfaces 层完成）+ 过期警示标记 */
    public ExportPayload export(String id, RenderTarget target) {
        RequirementSubmission submission = load(id);
        MdArtifact artifact = artifactRepository.findLatest(id, target)
                .orElseThrow(() -> EngineException.conflict(
                        "该需求尚未渲染此目标（" + target.value() + "），先发起渲染"));
        List<Attachment> attachments = attachmentRepository.findByRequirement(id);
        Map<String, byte[]> attachmentBytes = new LinkedHashMap<String, byte[]>();
        for (Attachment attachment : attachments) {
            attachmentBytes.put(attachment.getFileName(), attachmentStore.load(attachment.getStoredPath()));
        }
        submission.markExported();
        requirementRepository.update(submission);
        return new ExportPayload(submission, artifact, artifact.getFiles(), attachmentBytes, artifact.isStale());
    }

    /** 导出载荷（application 层的值容器，interfaces 转响应 DTO） */
    public static class ExportPayload {
        public final RequirementSubmission submission;
        public final MdArtifact artifact;
        public final Map<String, String> files;
        public final Map<String, byte[]> attachmentBytes;
        public final boolean stale;

        ExportPayload(RequirementSubmission submission, MdArtifact artifact,
                      Map<String, String> files, Map<String, byte[]> attachmentBytes, boolean stale) {
            this.submission = submission;
            this.artifact = artifact;
            this.files = files;
            this.attachmentBytes = attachmentBytes;
            this.stale = stale;
        }
    }

    // ---------- 查询 ----------

    /** 列表：状态/优先级/关键字过滤 + 创建时间倒序（内存过滤，PageResult.of 切页） */
    public List<RequirementSubmission> list(RequirementStatus status, String priority, String keyword) {
        List<RequirementSubmission> result = new ArrayList<RequirementSubmission>();
        String q = keyword == null ? "" : keyword.trim().toLowerCase();
        for (RequirementSubmission submission : requirementRepository.findAll()) {
            if (status != null && submission.getStatus() != status) {
                continue;
            }
            if (priority != null && !priority.trim().isEmpty()
                    && !priority.trim().equalsIgnoreCase(submission.getForm().getBasic().getPriority())) {
                continue;
            }
            if (!q.isEmpty() && !submission.getTitle().toLowerCase().contains(q)) {
                continue;
            }
            result.add(submission);
        }
        return result;
    }

    public RequirementDetail getDetail(String id) {
        RequirementSubmission submission = load(id);
        return new RequirementDetail(submission, attachmentRepository.findByRequirement(id),
                artifactRepository.findByRequirement(id));
    }

    // ---------- 附件 ----------

    public Attachment addAttachment(String id, String fileName, String contentType, byte[] bytes) {
        load(id);
        formatGuard.check(fileName, bytes);
        String storedPath = attachmentStore.store(id, fileName, bytes);
        return attachmentRepository.insert(new Attachment(-1L, id, fileName, storedPath,
                bytes.length, contentType == null ? "application/octet-stream" : contentType, Instant.now()));
    }

    public List<Attachment> listAttachments(String id) {
        load(id);
        return attachmentRepository.findByRequirement(id);
    }

    public byte[] loadAttachment(String id, long attachmentId) {
        load(id);
        Attachment attachment = attachmentRepository.findById(id, attachmentId)
                .orElseThrow(() -> EngineException.notFound("附件不存在: " + attachmentId));
        return attachmentStore.load(attachment.getStoredPath());
    }

    // ---------- 内部 ----------

    private RequirementSubmission load(String id) {
        return requirementRepository.findById(id)
                .orElseThrow(() -> EngineException.notFound("需求不存在: " + id));
    }

    private RequirementIR buildIR(RequirementSubmission submission) {
        return RequirementIR.of(submission, attachmentRepository.findByRequirement(submission.getId()));
    }

    /** slug 非空时必须是合法 kebab-case（它将成为 openspec 包里的目录名） */
    private void validateSlugIfPresent(RequirementForm form) {
        String slug = form.getBasic().getCapabilitySlug();
        if (slug != null && !slug.trim().isEmpty() && !slug.trim().matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw EngineException.badRequest(
                    "能力标识必须是 kebab-case 小写字母数字（如 order-export）: " + slug);
        }
    }

    private String join(List<String> items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
