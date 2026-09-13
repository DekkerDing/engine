package io.github.dekkerding.engine.domain.service.renderer;

import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VibeCoding 渲染器 —— 产出面向 AI 编码 Agent 的单一规范文档（TASK.md）。
 *
 * <p>固定七章节（spec-rendering「VibeCoding 范式结构」）：任务目标 / 业务背景 /
 * 需求详述 / 约束与边界 / 验收标准 / 实施注意事项 / 附件引用清单。
 * 验收标准保留 WHEN/THEN 结构化表述（Agent 可直接转为测试用例）。
 *
 * <p>「实施注意事项」内置两条通用纪律（最小改动 + 每场景有验证），
 * 这是把 VibeCoding 经验沉淀进模板的第一步——后期记忆系统会在此注入项目级记忆。
 */
/** 纯 domain 服务：由应用层 new 装配（沿 TextChunker 惯例，domain 零框架依赖） */
public class VibecodingRenderer implements SpecRenderer {

    static final String TEMPLATE_VERSION = "vibecoding-v1";

    @Override
    public RenderTarget target() {
        return RenderTarget.VIBECODING;
    }

    @Override
    public String templateVersion() {
        return TEMPLATE_VERSION;
    }

    @Override
    public Map<String, String> render(RequirementIR ir) {
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("TASK.md", renderTask(ir));
        return files;
    }

    private String renderTask(RequirementIR ir) {
        RequirementIR.Meta meta = ir.getMeta();
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务：").append(meta.title).append("\n\n");
        sb.append("> 本文档由需求工厂自动生成（需求单 ").append(meta.id)
                .append("），面向 AI 编码 Agent（Claude Code / Codex 等）。\n");
        sb.append("> 实施时严格按「验收标准」逐条核对；遇到与现状冲突处，先提出再动手。\n\n");

        sb.append("## 任务目标\n\n");
        for (RequirementForm.Feature feature : ir.getFeatures()) {
            RequirementForm.UserStory story = feature.getUserStory();
            sb.append("- 作为").append(orBlank(story.getAs(), "？"))
                    .append("，").append(orBlank(story.getWant(), "？"))
                    .append("，以便").append(orBlank(story.getSo(), "？")).append("\n");
        }
        sb.append("\n");

        sb.append("## 业务背景\n\n");
        appendBullets(sb, or(ir.getBackground().getPainPoints(), "（业务方未填写痛点）"));
        sb.append("\n影响范围：").append(orBlank(ir.getBackground().getImpactScope(), "（未填写）")).append("\n\n");

        sb.append("## 需求详述\n\n");
        int index = 0;
        for (RequirementForm.Feature feature : ir.getFeatures()) {
            index++;
            RequirementForm.UserStory story = feature.getUserStory();
            sb.append("### 功能 ").append(index).append("：")
                    .append(orBlank(story.getWant(), "未命名功能")).append("\n\n");
            sb.append("用户故事：作为").append(orBlank(story.getAs(), "？"))
                    .append("，想要").append(orBlank(story.getWant(), "？"))
                    .append("，以便").append(orBlank(story.getSo(), "？")).append("。\n\n");
            if (!feature.getDetails().trim().isEmpty()) {
                sb.append(feature.getDetails().trim()).append("\n\n");
            }
        }

        sb.append("## 约束与边界\n\n");
        sb.append("技术约束：\n");
        appendBullets(sb, or(ir.getConstraints().getTechnical(), "-（无）"));
        sb.append("合规约束：\n");
        appendBullets(sb, or(ir.getConstraints().getCompliance(), "-（无）"));
        sb.append("范围边界：\n");
        sb.append("- 只做本文件描述的行为；超出范围的新发现先回报，不顺手实现\n");
        sb.append("- 不改无关代码；重构冲动记入回报清单\n\n");

        sb.append("## 验收标准\n\n");
        int scenario = 0;
        for (RequirementForm.AcceptanceCriterion criterion : ir.getAcceptance()) {
            scenario++;
            sb.append("### 场景 ").append(scenario).append("\n");
            sb.append("- **WHEN** ").append(orBlank(criterion.getWhen(), "（未填写）")).append("\n");
            sb.append("- **THEN** ").append(orBlank(criterion.getThen(), "（未填写）")).append("\n");
        }
        sb.append("\n");

        sb.append("## 实施注意事项\n\n");
        sb.append("- 修改保持最小化：每个动作都能对应到本文件的某条需求\n");
        sb.append("- 每个 WHEN/THEN 场景都要有对应的验证方式（自动化测试优先，其次手工步骤）\n");
        for (String constraint : ir.getConstraints().getTechnical()) {
            sb.append("- 技术约束提醒：").append(constraint == null ? "" : constraint.trim()).append("\n");
        }
        sb.append("\n");

        sb.append("## 附件引用清单\n\n");
        List<String> attachmentLines = new ArrayList<String>();
        for (RequirementIR.AttachmentRef ref : ir.getAttachments()) {
            attachmentLines.add(ref.name + "（" + ref.type + "）");
        }
        appendBullets(sb, or(attachmentLines, "（无附件）"));
        return sb.toString();
    }

    private void appendBullets(StringBuilder sb, List<String> lines) {
        for (String line : lines) {
            String text = line == null ? "" : line.trim();
            // 约束占位 "-（无）" 已带符号，避免二次加杠
            if (text.startsWith("-")) {
                sb.append(text).append("\n");
            } else {
                sb.append("- ").append(text).append("\n");
            }
        }
    }

    private List<String> or(List<String> list, String placeholder) {
        if (list == null || list.isEmpty()) {
            List<String> single = new ArrayList<String>();
            single.add(placeholder);
            return single;
        }
        return list;
    }

    private String orBlank(String value, String placeholder) {
        return value == null || value.trim().isEmpty() ? placeholder : value.trim();
    }
}
