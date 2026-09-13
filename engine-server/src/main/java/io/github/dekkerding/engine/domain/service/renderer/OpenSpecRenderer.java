package io.github.dekkerding.engine.domain.service.renderer;

import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenSpec 渲染器 —— 产出可被 openspec CLI 直接消费的变更包。
 *
 * <p>产物结构（.openspec.yaml + proposal.md + tasks.md + specs/&lt;slug&gt;/spec.md），
 * 放进目标仓库 openspec/changes/&lt;name&gt;/ 即成为合法变更（任务 2.3 用真实 CLI 验证）。
 *
 * <p>【模板即代码】模板是本类内的字符串常量（JDK 8 无 text block，用拼接）：
 * 模板变更 = 代码变更 = 走评审与测试；TEMPLATE_VERSION 随实现递增并写入工件行。
 *
 * <p>【映射决策】表单的验收标准是需求级（不按功能拆分），因此每条功能的
 * Requirement 都挂全部验收场景——重复是显式的、与表单模型一致的诚实取舍。
 */
/** 纯 domain 服务：由应用层 new 装配（沿 TextChunker 惯例，domain 零框架依赖） */
public class OpenSpecRenderer implements SpecRenderer {

    static final String TEMPLATE_VERSION = "openspec-v1";

    @Override
    public RenderTarget target() {
        return RenderTarget.OPENSPEC;
    }

    @Override
    public String templateVersion() {
        return TEMPLATE_VERSION;
    }

    @Override
    public Map<String, String> render(RequirementIR ir) {
        Map<String, String> files = new LinkedHashMap<String, String>();
        // 注意：.openspec.yaml 不写 created 日期——确定性纪律（同 IR 同输出）优先于元信息
        files.put(".openspec.yaml", "schema: spec-driven\n");
        files.put("proposal.md", renderProposal(ir));
        files.put("tasks.md", renderTasks(ir));
        files.put("specs/" + ir.getMeta().capabilitySlug + "/spec.md", renderSpec(ir));
        return files;
    }

    // ---------- proposal.md ----------

    private String renderProposal(RequirementIR ir) {
        RequirementIR.Meta meta = ir.getMeta();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(meta.title).append("\n\n");
        sb.append("> 由需求工厂自动生成（需求单 ").append(meta.id)
                .append(" · 能力 ").append(meta.capabilitySlug).append("）。\n\n");

        sb.append("## Why\n\n");
        appendBullets(sb, or(ir.getBackground().getPainPoints(), "（业务方未填写痛点）"));
        sb.append("\n影响范围：").append(orBlank(ir.getBackground().getImpactScope(), "（未填写）")).append("\n");
        if (!ir.getBackground().getMetrics().isEmpty()) {
            sb.append("\n量化指标：\n");
            appendBullets(sb, ir.getBackground().getMetrics());
        }

        sb.append("\n## What Changes\n\n");
        for (RequirementForm.Feature feature : ir.getFeatures()) {
            RequirementForm.UserStory story = feature.getUserStory();
            sb.append("- 作为").append(orBlank(story.getAs(), "？"))
                    .append("，").append(orBlank(story.getWant(), "？"))
                    .append("，以便").append(orBlank(story.getSo(), "？")).append("\n");
            if (!feature.getDetails().trim().isEmpty()) {
                sb.append("  ").append(feature.getDetails().trim().replace("\n", "\n  ")).append("\n");
            }
        }

        sb.append("\n## Impact\n\n");
        sb.append("- 提出人：").append(orBlank(meta.submitter, "未署名"));
        if (!meta.department.trim().isEmpty()) {
            sb.append("（").append(meta.department).append("）");
        }
        sb.append("\n");
        sb.append("- 优先级：").append(meta.priority).append("\n");
        sb.append("- 期望上线：").append(orBlank(meta.expectDate, "未指定")).append("\n");
        sb.append("- 技术约束：\n");
        appendBullets(sb, or(ir.getConstraints().getTechnical(), "（无）"));
        sb.append("- 合规约束：\n");
        appendBullets(sb, or(ir.getConstraints().getCompliance(), "（无）"));
        sb.append("- 附件：\n");
        List<String> attachmentLines = new ArrayList<String>();
        for (RequirementIR.AttachmentRef ref : ir.getAttachments()) {
            attachmentLines.add(ref.name + "（" + ref.type + "）");
        }
        appendBullets(sb, or(attachmentLines, "（无）"));
        return sb.toString();
    }

    // ---------- specs/<slug>/spec.md ----------

    private String renderSpec(RequirementIR ir) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Purpose\n\n");
        sb.append(ir.getMeta().title).append(" —— 由需求单 ").append(ir.getMeta().id)
                .append(" 派生的能力规格。\n\n");
        sb.append("## ADDED Requirements\n\n");

        int index = 0;
        for (RequirementForm.Feature feature : ir.getFeatures()) {
            index++;
            RequirementForm.UserStory story = feature.getUserStory();
            sb.append("### Requirement: ").append(index).append(". ")
                    .append(orBlank(story.getWant(), "未命名功能")).append("\n\n");
            sb.append("作为").append(orBlank(story.getAs(), "？"))
                    .append("，想要").append(orBlank(story.getWant(), "？"))
                    .append("，以便").append(orBlank(story.getSo(), "？")).append("。\n\n");
            if (!feature.getDetails().trim().isEmpty()) {
                sb.append(feature.getDetails().trim()).append("\n\n");
            }
            int scenario = 0;
            for (RequirementForm.AcceptanceCriterion criterion : ir.getAcceptance()) {
                scenario++;
                sb.append("#### Scenario: 验收场景 ").append(scenario).append("\n");
                sb.append("- **WHEN** ").append(orBlank(criterion.getWhen(), "（未填写）")).append("\n");
                sb.append("- **THEN** ").append(orBlank(criterion.getThen(), "（未填写）")).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---------- tasks.md ----------

    private String renderTasks(RequirementIR ir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 实施任务清单\n\n");
        sb.append("> 由需求工厂从需求单 ").append(ir.getMeta().id)
                .append(" 自动派生；实施时可按仓库惯例增补。\n\n");

        int index = 0;
        for (RequirementForm.Feature feature : ir.getFeatures()) {
            index++;
            String want = orBlank(feature.getUserStory().getWant(), "未命名功能");
            sb.append("## ").append(index).append(". ").append(want).append("\n\n");
            sb.append("- [ ] ").append(index).append(".1 实现功能：").append(want).append("\n");
            sb.append("- [ ] ").append(index).append(".2 为「").append(want).append("」编写测试（覆盖验收场景）\n");
            sb.append("- [ ] ").append(index).append(".3 核对验收场景全部通过\n\n");
        }

        sb.append("## 验收核对\n\n");
        int scenario = 0;
        for (RequirementForm.AcceptanceCriterion criterion : ir.getAcceptance()) {
            scenario++;
            sb.append("- [ ] 场景").append(scenario).append("：WHEN ")
                    .append(orBlank(criterion.getWhen(), "（未填写）"))
                    .append(" THEN ").append(orBlank(criterion.getThen(), "（未填写）")).append("\n");
        }
        return sb.toString();
    }

    // ---------- 小工具 ----------

    /** 列表转 "- " 项目符号块（空列表给占位文案）；null 防御返回单元素占位 */
    private void appendBullets(StringBuilder sb, List<String> lines) {
        for (String line : lines) {
            sb.append("- ").append(line == null ? "" : line.trim()).append("\n");
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
