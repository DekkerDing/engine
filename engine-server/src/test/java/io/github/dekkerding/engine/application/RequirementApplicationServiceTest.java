package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;
import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementStatus;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;
import io.github.dekkerding.engine.domain.service.RequirementTestFixtures;
import io.github.dekkerding.engine.infrastructure.persistence.DatabaseMigrator;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteAttachmentRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteMdArtifactRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteRequirementRepository;
import io.github.dekkerding.engine.infrastructure.requirement.AttachmentFormatGuard;
import io.github.dekkerding.engine.infrastructure.requirement.LocalFileAttachmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求工厂应用层测试 —— 任务 3.1/3.2 验收：
 * 状态机推进 / 编辑回退+过期联动 / 版本递增 / 修订覆盖 / 导出 / 附件守卫。
 * 全真实件（SQLite @TempDir + 文件存储），零替身——被测的是编排逻辑本身。
 */
class RequirementApplicationServiceTest {

    @TempDir
    Path tempDir;

    private RequirementApplicationService service;
    private SqliteConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new SqliteConnectionManager(
                tempDir.resolve("engine.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate();

        SqliteRequirementRepository requirementRepository = new SqliteRequirementRepository(connectionManager);
        SqliteMdArtifactRepository artifactRepository = new SqliteMdArtifactRepository(connectionManager);
        SqliteAttachmentRepository attachmentRepository = new SqliteAttachmentRepository(connectionManager);
        LocalFileAttachmentStore attachmentStore = new LocalFileAttachmentStore(
                tempDir.resolve("requirements").toString());
        AttachmentFormatGuard formatGuard = new AttachmentFormatGuard();

        service = new RequirementApplicationService(
                requirementRepository, artifactRepository, attachmentRepository, attachmentStore, formatGuard);
    }

    // ---------- 生命周期 ----------

    @Test
    void 创建即为草稿且slug缺省生成() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        assertEquals(RequirementStatus.DRAFT, submission.getStatus());
        assertEquals("order-export", submission.getCapabilitySlug());
        assertNotNull(service.getDetail(submission.getId()));
    }

    @Test
    void 闸门拒绝_缺验收标准返回逐项清单() {
        RequirementForm form = RequirementTestFixtures.fullForm();
        form.setAcceptance(Arrays.<RequirementForm.AcceptanceCriterion>asList());
        RequirementSubmission submission = service.create(form);

        EngineException e = assertThrows(EngineException.class, () -> service.submit(submission.getId()));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("验收标准至少一条"));
        assertEquals(RequirementStatus.DRAFT, service.getDetail(submission.getId()).getSubmission().getStatus());
    }

    @Test
    void 编辑保存_状态回退草稿_全部工件过期() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        service.submit(submission.getId());
        MdArtifact first = service.render(submission.getId(), RenderTarget.OPENSPEC);
        MdArtifact second = service.render(submission.getId(), RenderTarget.VIBECODING);
        assertFalse(first.isStale());
        assertFalse(second.isStale());

        RequirementForm edited = RequirementTestFixtures.fullForm();
        edited.getBasic().setTitle("订单导出（改）");
        service.save(submission.getId(), edited);

        assertEquals(RequirementStatus.DRAFT,
                service.getDetail(submission.getId()).getSubmission().getStatus());
        assertTrue(service.getArtifact(submission.getId(), RenderTarget.OPENSPEC, 1).isStale(), "openspec 工件必须过期");
        assertTrue(service.getArtifact(submission.getId(), RenderTarget.VIBECODING, 1).isStale(), "vibecoding 工件必须过期");
    }

    @Test
    void 渲染前置校验_未提交的需求渲染被409拒绝() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        EngineException e = assertThrows(EngineException.class,
                () -> service.render(submission.getId(), RenderTarget.OPENSPEC));
        assertEquals(409, e.getCode());
    }

    @Test
    void 渲染版本单调递增_同目标重渲染() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        service.submit(submission.getId());
        assertEquals(1, service.render(submission.getId(), RenderTarget.OPENSPEC).getVersion());
        assertEquals(2, service.render(submission.getId(), RenderTarget.OPENSPEC).getVersion());
        // 双目标版本号各自独立计数
        assertEquals(1, service.render(submission.getId(), RenderTarget.VIBECODING).getVersion());
    }

    // ---------- 修订与导出 ----------

    @Test
    void 修订整体覆盖_导出以修订为准_过期工件拒绝修订() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        service.submit(submission.getId());
        MdArtifact artifact = service.render(submission.getId(), RenderTarget.VIBECODING);

        Map<String, String> revised = new LinkedHashMap<String, String>();
        revised.put("TASK.md", "# 任务：修订版\n");
        service.saveRevision(submission.getId(), RenderTarget.VIBECODING, artifact.getVersion(), revised);
        assertEquals("# 任务：修订版\n",
                service.getArtifact(submission.getId(), RenderTarget.VIBECODING, 1).getFiles().get("TASK.md"));
        // 模板原始输出不受修订影响（diff 基准完好）
        assertTrue(service.getArtifact(submission.getId(), RenderTarget.VIBECODING, 1)
                .getTemplateOutput().get("TASK.md").contains("订单导出"));

        RequirementApplicationService.ExportPayload payload = service.export(submission.getId(), RenderTarget.VIBECODING);
        assertEquals("# 任务：修订版\n", new String(payload.files.get("TASK.md")));
        assertEquals(RequirementStatus.EXPORTED, payload.submission.getStatus());

        // 编辑后过期 → 修订被 409 拒绝
        service.save(submission.getId(), RequirementTestFixtures.fullForm());
        EngineException e = assertThrows(EngineException.class,
                () -> service.saveRevision(submission.getId(), RenderTarget.VIBECODING, 1, revised));
        assertEquals(409, e.getCode());
    }

    @Test
    void 导出openspec包_含附件字节() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        service.submit(submission.getId());
        service.addAttachment(submission.getId(), "原型.png", "image/png", pngBytes());
        service.render(submission.getId(), RenderTarget.OPENSPEC);

        RequirementApplicationService.ExportPayload payload = service.export(submission.getId(), RenderTarget.OPENSPEC);
        assertTrue(payload.files.containsKey(".openspec.yaml"));
        assertTrue(payload.files.containsKey("proposal.md"));
        assertTrue(payload.files.containsKey("tasks.md"));
        assertTrue(payload.files.containsKey("specs/order-export/spec.md"));
        assertEquals(1, payload.attachmentBytes.size(), "附件必须随导出包携带");
        assertFalse(payload.stale);
    }

    @Test
    void 未渲染的目标导出被409拒绝() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        service.submit(submission.getId());
        EngineException e = assertThrows(EngineException.class,
                () -> service.export(submission.getId(), RenderTarget.OPENSPEC));
        assertEquals(409, e.getCode());
    }

    // ---------- 附件守卫 ----------

    @Test
    void 附件魔数校验_exe伪装png被拒() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        byte[] fakePng = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x01, 0x02, 0x03};
        EngineException e = assertThrows(EngineException.class,
                () -> service.addAttachment(submission.getId(), "malware.png", "image/png", fakePng));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("魔数"));
    }

    @Test
    void 附件白名单外格式被拒_合法png通过() {
        RequirementSubmission submission = service.create(RequirementTestFixtures.fullForm());
        EngineException e = assertThrows(EngineException.class,
                () -> service.addAttachment(submission.getId(), "tool.exe", "application/x-exe", new byte[]{1, 2, 3}));
        assertEquals(400, e.getCode());

        Attachment stored = service.addAttachment(submission.getId(), "shot.png", "image/png", pngBytes());
        assertTrue(stored.getId() > 0);
        assertEquals(1, service.listAttachments(submission.getId()).size());
        assertEquals(8, service.loadAttachment(submission.getId(), stored.getId()).length);
    }

    @Test
    void 列表过滤_状态与关键字() {
        service.create(RequirementTestFixtures.fullForm());
        RequirementForm draft = RequirementTestFixtures.fullForm();
        draft.getBasic().setTitle("会员积分");
        service.create(draft);

        assertEquals(2, service.list(null, null, null).size());
        assertEquals(2, service.list(RequirementStatus.DRAFT, null, null).size());
        assertEquals(1, service.list(null, null, "会员").size());
        assertEquals(0, service.list(RequirementStatus.EXPORTED, null, null).size());
    }

    // ---------- 造数 ----------

    /** 最小合法 PNG（8 字节魔数 + IHDR 片断头，够守卫校验） */
    private byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }
}
