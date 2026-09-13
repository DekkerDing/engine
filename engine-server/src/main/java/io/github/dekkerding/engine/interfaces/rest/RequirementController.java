package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.RequirementApplicationService;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;
import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementStatus;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;
import io.github.dekkerding.engine.interfaces.rest.dto.PageResult;
import io.github.dekkerding.engine.interfaces.rest.dto.RequirementDto.ArtifactContentResponse;
import io.github.dekkerding.engine.interfaces.rest.dto.RequirementDto.ArtifactSummary;
import io.github.dekkerding.engine.interfaces.rest.dto.RequirementDto.AttachmentResponse;
import io.github.dekkerding.engine.interfaces.rest.dto.RequirementDto.RequirementDetailResponse;
import io.github.dekkerding.engine.interfaces.rest.dto.RequirementDto.RequirementSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 需求工厂接口 —— 表单收集 / 渲染 / 工件 / 导出 / 附件 全部入口（design D6 契约）。
 *
 * <p>【教学注释 · Controller 的克制】沿 DocumentController 纪律：接参数 → 调应用服务
 * → 包响应，三行以内；闸门/状态机/渲染全在应用层与领域层。
 *
 * <p>导出是唯一返回二进制的端点（zip/md），不走 ApiResponse 信封——
 * 下载流就是 data 本身；过期警示通过 X-Artifact-Stale 头传递。
 */
@RestController
@RequestMapping("/api/requirements")
public class RequirementController {

    private static final Logger log = LoggerFactory.getLogger(RequirementController.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final RequirementApplicationService requirementService;

    public RequirementController(RequirementApplicationService requirementService) {
        this.requirementService = requirementService;
    }

    // ---------- 需求 CRUD ----------

    @PostMapping
    public ApiResponse<RequirementSummary> create(@RequestBody RequirementForm form) {
        if (form == null) {
            throw EngineException.badRequest("请求体缺少表单数据");
        }
        RequirementSubmission submission = requirementService.create(form);
        return ApiResponse.ok(new RequirementSummary(submission));
    }

    @GetMapping
    public ApiResponse<PageResult<RequirementSummary>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        RequirementStatus statusFilter = parseStatus(status);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);

        List<RequirementSummary> items = new ArrayList<RequirementSummary>();
        for (RequirementSubmission submission : requirementService.list(statusFilter, priority, q)) {
            items.add(new RequirementSummary(submission));
        }
        return ApiResponse.ok(PageResult.of(items, safePage, safeSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<RequirementDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(toDetail(requirementService.getDetail(id)));
    }

    @PutMapping("/{id}")
    public ApiResponse<RequirementDetailResponse> save(@PathVariable String id, @RequestBody RequirementForm form) {
        if (form == null) {
            throw EngineException.badRequest("请求体缺少表单数据");
        }
        requirementService.save(id, form);
        return ApiResponse.ok(toDetail(requirementService.getDetail(id)));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<RequirementSummary> submit(@PathVariable String id) {
        return ApiResponse.ok(new RequirementSummary(requirementService.submit(id)));
    }

    // ---------- 渲染与工件 ----------

    @PostMapping("/{id}/render")
    public ApiResponse<ArtifactSummary> render(@PathVariable String id, @RequestBody RenderRequest request) {
        if (request == null || request.target == null) {
            throw EngineException.badRequest("请求体缺少 target（openspec/vibecoding）");
        }
        MdArtifact artifact = requirementService.render(id, parseTarget(request.target));
        return ApiResponse.ok(new ArtifactSummary(artifact));
    }

    @GetMapping("/{id}/artifacts")
    public ApiResponse<List<ArtifactSummary>> artifacts(@PathVariable String id) {
        List<ArtifactSummary> items = new ArrayList<ArtifactSummary>();
        for (MdArtifact artifact : requirementService.listArtifacts(id)) {
            items.add(new ArtifactSummary(artifact));
        }
        return ApiResponse.ok(items);
    }

    @GetMapping("/{id}/artifacts/{version}")
    public ApiResponse<ArtifactContentResponse> artifact(
            @PathVariable String id, @PathVariable int version,
            @RequestParam("target") String target) {
        MdArtifact artifact = requirementService.getArtifact(id, parseTarget(target), version);
        return ApiResponse.ok(new ArtifactContentResponse(artifact));
    }

    @PutMapping("/{id}/artifacts/{version}")
    public ApiResponse<ArtifactSummary> saveRevision(
            @PathVariable String id, @PathVariable int version, @RequestBody RevisionRequest request) {
        if (request == null || request.target == null || request.files == null || request.files.isEmpty()) {
            throw EngineException.badRequest("请求体缺少 target 或 files");
        }
        MdArtifact artifact = requirementService.saveRevision(id, parseTarget(request.target), version, request.files);
        return ApiResponse.ok(new ArtifactSummary(artifact));
    }

    // ---------- 导出 ----------

    /**
     * 导出下载：openspec → zip（变更包 + attachments/）；vibecoding → 单 TASK.md。
     * X-Artifact-Stale: true 表示工件基于旧版需求（前端导出前警示）。
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam("target") String target) {
        RequirementApplicationService.ExportPayload payload = requirementService.export(id, parseTarget(target));
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Artifact-Stale", Boolean.toString(payload.stale));
        headers.add("X-Artifact-Version", String.valueOf(payload.artifact.getVersion()));
        headers.add("X-Requirement-Id", id);

        if (payload.artifact.getTarget() == RenderTarget.VIBECODING) {
            byte[] body = singleFileContent(payload.files);
            headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"TASK-" + payload.submission.getCapabilitySlug() + ".md\"");
            return ResponseEntity.ok().headers(headers).body(body);
        }
        byte[] zip = buildZip(payload);
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + payload.submission.getCapabilitySlug() + "-openspec.zip\"");
        return ResponseEntity.ok().headers(headers).body(zip);
    }

    // ---------- 附件 ----------

    @PostMapping("/{id}/attachments")
    public ApiResponse<AttachmentResponse> uploadAttachment(
            @PathVariable String id, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw EngineException.badRequest("上传附件为空（multipart 字段名必须是 file）");
        }
        try {
            Attachment attachment = requirementService.addAttachment(
                    id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
            return ApiResponse.ok(new AttachmentResponse(attachment));
        } catch (IOException e) {
            throw EngineException.internal("读取附件内容失败: " + file.getOriginalFilename(), e);
        }
    }

    @GetMapping("/{id}/attachments")
    public ApiResponse<List<AttachmentResponse>> attachments(@PathVariable String id) {
        List<AttachmentResponse> items = new ArrayList<AttachmentResponse>();
        for (Attachment attachment : requirementService.listAttachments(id)) {
            items.add(new AttachmentResponse(attachment));
        }
        return ApiResponse.ok(items);
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable String id, @PathVariable long attachmentId) {
        Attachment attachment = requirementService.listAttachments(id).stream()
                .filter(a -> a.getId() == attachmentId)
                .findFirst()
                .orElseThrow(() -> EngineException.notFound("附件不存在: " + attachmentId));
        byte[] bytes = requirementService.loadAttachment(id, attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + attachment.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(
                        attachment.getContentType() == null ? "application/octet-stream" : attachment.getContentType()))
                .body(bytes);
    }

    // ---------- 请求体 ----------

    public static class RenderRequest {
        public String target;
    }

    public static class RevisionRequest {
        public String target;
        public Map<String, String> files;
    }

    // ---------- 组装与翻译 ----------

    private RequirementDetailResponse toDetail(io.github.dekkerding.engine.application.dto.RequirementDetail detail) {
        RequirementSummary summary = new RequirementSummary(detail.getSubmission());
        List<AttachmentResponse> attachments = new ArrayList<AttachmentResponse>();
        for (Attachment attachment : detail.getAttachments()) {
            attachments.add(new AttachmentResponse(attachment));
        }
        List<ArtifactSummary> artifacts = new ArrayList<ArtifactSummary>();
        for (MdArtifact artifact : detail.getArtifacts()) {
            artifacts.add(new ArtifactSummary(artifact));
        }
        return new RequirementDetailResponse(summary, detail.getSubmission().getForm(), attachments, artifacts);
    }

    /** zip 组装：变更包文件在根目录、附件进 attachments/（文件名冲突时后者覆盖——附件本就是附属资源） */
    private byte[] buildZip(RequirementApplicationService.ExportPayload payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, String> entry : payload.files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : payload.attachmentBytes.entrySet()) {
                zip.putNextEntry(new ZipEntry("attachments/" + entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw EngineException.internal("导出 zip 组装失败", e);
        }
        return buffer.toByteArray();
    }

    private byte[] singleFileContent(Map<String, String> files) {
        // vibecoding 产物恒为单文件 TASK.md；取首个值（防御：模板变更时这里会显式暴露）
        for (String content : files.values()) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
        throw EngineException.internal("vibecoding 产物为空", new IllegalStateException("files map empty"));
    }

    private RenderTarget parseTarget(String value) {
        try {
            return RenderTarget.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw EngineException.badRequest(e.getMessage());
        }
    }

    private RequirementStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        try {
            return RequirementStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw EngineException.badRequest(
                    "无效的状态过滤值: " + status + "（合法: DRAFT/SUBMITTED/EXPORTED）");
        }
    }
}
