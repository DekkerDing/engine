package io.github.dekkerding.engine.domain.service;

import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求完整性闸门（纯函数）—— 提交前的守门员。
 *
 * <p>【教学注释 · 为什么是纯函数】无状态、无 IO：同一个 IR 进来，
 * 同一份缺失清单出去——可以直接单测，不依赖任何替身。
 * 闸门规则与渲染目标无关（作用在 IR 上，两个渲染器共享同一道门）。
 *
 * <p>规则（spec requirement-intake「RequirementIR 完整性闸门」）：
 * 标题非空、期望功能 ≥ 1、每条用户故事三要素齐备、验收标准 ≥ 1。
 */
public class RequirementValidator {

    /** 校验：返回缺失项清单（空 = 通过）。不抛异常——让应用层决定错误呈现方式 */
    public List<String> validate(RequirementIR ir) {
        List<String> missing = new ArrayList<String>();

        if (ir.getMeta().title.trim().isEmpty()) {
            missing.add("需求标题不能为空");
        }
        if (ir.getFeatures() == null || ir.getFeatures().isEmpty()) {
            missing.add("期望功能至少一条");
        } else {
            for (int i = 0; i < ir.getFeatures().size(); i++) {
                RequirementForm.UserStory story = ir.getFeatures().get(i).getUserStory();
                String as = story == null || story.getAs() == null ? "" : story.getAs().trim();
                String want = story == null || story.getWant() == null ? "" : story.getWant().trim();
                String so = story == null || story.getSo() == null ? "" : story.getSo().trim();
                if (as.isEmpty() || want.isEmpty() || so.isEmpty()) {
                    missing.add("第 " + (i + 1) + " 条用户故事三要素不齐备（作为谁/想要什么/以便什么）");
                }
            }
        }
        if (ir.getAcceptance() == null || ir.getAcceptance().isEmpty()) {
            missing.add("验收标准至少一条");
        } else {
            for (int i = 0; i < ir.getAcceptance().size(); i++) {
                RequirementForm.AcceptanceCriterion criterion = ir.getAcceptance().get(i);
                String when = criterion.getWhen() == null ? "" : criterion.getWhen().trim();
                String then = criterion.getThen() == null ? "" : criterion.getThen().trim();
                if (when.isEmpty() || then.isEmpty()) {
                    missing.add("第 " + (i + 1) + " 条验收标准 WHEN/THEN 不完整");
                }
            }
        }
        return missing;
    }
}
