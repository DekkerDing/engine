package io.github.dekkerding.engine.domain.service.renderer;

import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;
import io.github.dekkerding.engine.domain.service.RequirementTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染引擎测试 —— 任务 2.1/2.2 验收：
 * ①确定性（同 IR 两次渲染字节级相同）②产物结构完整性（关键章节/结构头逐一存在）。
 *
 * <p>【测试策略】断言"结构锚点 + 关键内容行"而非全文快照：快照测试在模板
 * 正常演进时噪音响亮；锚点断言在结构被破坏时才响（保留确定性验证的
 * 字节级断言——两个实例各渲染两次比较）。
 */
class RendererDeterminismTest {

    private final OpenSpecRenderer openSpecRenderer = new OpenSpecRenderer();
    private final VibecodingRenderer vibecodingRenderer = new VibecodingRenderer();

    @Test
    void openspec_产物确定性_两次渲染字节级相同() {
        RequirementIR ir = irOf();
        Map<String, String> first = openSpecRenderer.render(ir);
        Map<String, String> second = openSpecRenderer.render(ir);
        assertEquals(first.keySet(), second.keySet(), "文件清单必须一致");
        for (String name : first.keySet()) {
            assertArrayEquals(first.get(name).getBytes(), second.get(name).getBytes(),
                    "文件内容必须字节级相同: " + name);
        }
    }

    @Test
    void openspec_产物结构合法() {
        Map<String, String> files = openSpecRenderer.render(irOf());
        assertTrue(files.containsKey(".openspec.yaml"), "缺 .openspec.yaml");
        assertTrue(files.get(".openspec.yaml").startsWith("schema: spec-driven"));

        String proposal = files.get("proposal.md");
        assertTrue(proposal.contains("## Why"));
        assertTrue(proposal.contains("## What Changes"));
        assertTrue(proposal.contains("## Impact"));
        assertTrue(proposal.contains("手工导出太慢"), "痛点必须进入 Why");
        assertTrue(proposal.contains("导出上限 10 万行"), "技术约束必须进入 Impact");

        String spec = files.get("specs/order-export/spec.md");
        assertTrue(spec.startsWith("## Purpose"));
        assertTrue(spec.contains("## ADDED Requirements"));
        assertTrue(spec.contains("### Requirement: 1. 一键导出订单"));
        assertTrue(spec.contains("#### Scenario: 验收场景 1"));
        assertTrue(spec.contains("- **WHEN** 点击导出按钮"));
        assertTrue(spec.contains("- **THEN** 生成 csv 并下载"));
        // 4 级标题场景数 = 验收标准数 × 功能数（表单级验收挂到每条功能）
        assertEquals(4, countOccurrences(spec, "#### Scenario:"), "2 功能 × 2 验收 = 4 场景");

        String tasks = files.get("tasks.md");
        assertTrue(tasks.contains("- [ ] 1.1 实现功能：一键导出订单"));
        assertTrue(tasks.contains("- [ ] 2.2 为「查看导出记录」编写测试"));
    }

    @Test
    void vibecoding_产物确定性_两次渲染字节级相同() {
        Map<String, String> first = vibecodingRenderer.render(irOf());
        Map<String, String> second = vibecodingRenderer.render(irOf());
        assertArrayEquals(first.get("TASK.md").getBytes(), second.get("TASK.md").getBytes());
    }

    @Test
    void vibecoding_七章节齐备且验收保留_when_then() {
        String task = vibecodingRenderer.render(irOf()).get("TASK.md");
        assertTrue(task.contains("## 任务目标"));
        assertTrue(task.contains("## 业务背景"));
        assertTrue(task.contains("## 需求详述"));
        assertTrue(task.contains("## 约束与边界"));
        assertTrue(task.contains("## 验收标准"));
        assertTrue(task.contains("## 实施注意事项"));
        assertTrue(task.contains("## 附件引用清单"));
        assertTrue(task.contains("- **WHEN** 点击导出按钮"), "WHEN/THEN 必须结构化保留");
        assertTrue(task.contains("- **THEN** 生成 csv 并下载"));
        assertTrue(task.contains("- 原型.png（image/png）"), "附件引用清单必须列出附件");
    }

    @Test
    void 双目标语义一致_验收标准条目一一对应() {
        Map<String, String> openSpec = openSpecRenderer.render(irOf());
        String vibecoding = vibecodingRenderer.render(irOf()).get("TASK.md");
        for (String when : new String[]{"点击导出按钮", "导出完成"}) {
            assertTrue(openSpec.get("specs/order-export/spec.md").contains(when), "openspec 缺: " + when);
            assertTrue(vibecoding.contains(when), "vibecoding 缺: " + when);
        }
    }

    @Test
    void 空可选字段的占位文案稳定() {
        RequirementForm form = RequirementTestFixtures.fullForm();
        form.getBackground().setPainPoints(null);
        form.getConstraints().setTechnical(null);
        form.ensureDefaults();
        Map<String, String> files = openSpecRenderer.render(irOf(form));
        assertTrue(files.get("proposal.md").contains("（业务方未填写痛点）"));
        // 空列表 ensureDefaults 后变空列表 → 占位
        assertTrue(files.get("proposal.md").contains("（无）"));
    }

    // ---------- 造数 ----------

    private RequirementIR irOf() {
        return irOf(RequirementTestFixtures.fullForm());
    }

    private RequirementIR irOf(RequirementForm form) {
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        RequirementSubmission submission = new RequirementSubmission(id, form, Instant.parse("2026-09-13T00:00:00Z"));
        Attachment attachment = new Attachment(1L, id, "原型.png",
                "01234567/attachments/x.png", 1024L, "image/png", Instant.parse("2026-09-13T00:00:00Z"));
        return RequirementIR.of(submission, Arrays.asList(attachment));
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    /**
     * 产物落盘（任务 2.3 的素材）：把 openspec 渲染结果写到 build/openspec-package-check/，
     * 供外部脚本在临时 openspec 根目录里执行真实 CLI 校验（validate/list）。
     * 路径固定可重复——每次跑测试都会刷新为最新模板输出。
     */
    @Test
    void 导出openspec产物到磁盘_供CLI校验() throws java.io.IOException {
        java.nio.file.Path out = java.nio.file.Paths.get(
                "build", "openspec-package-check", "order-export");
        java.nio.file.Files.createDirectories(out.resolve("specs"));
        Map<String, String> files = openSpecRenderer.render(irOf());
        for (Map.Entry<String, String> entry : files.entrySet()) {
            java.nio.file.Path target = out.resolve(entry.getKey());
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(java.nio.file.Files.exists(out.resolve("proposal.md")));
    }
}
