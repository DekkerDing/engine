package io.github.dekkerding.engine.domain.service;

import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;

import java.util.Arrays;
import java.util.Collections;

/**
 * 需求域测试夹具 —— 一个闸门全绿的标准表单（两个功能 × 两条验收 + 附件场景由调用方补）。
 * 造数集中一处，模板断言与用例测试共用同一份事实。
 */
public final class RequirementTestFixtures {

    private RequirementTestFixtures() {
    }

    /** 标准"订单导出"表单：标题/提交人/两功能/两验收/技术约束齐备 */
    public static RequirementForm fullForm() {
        RequirementForm form = new RequirementForm();
        form.getBasic().setTitle("订单导出");
        form.getBasic().setSubmitter("张三");
        form.getBasic().setDepartment("运营部");
        form.getBasic().setPriority("HIGH");
        form.getBasic().setExpectDate("2026-10-01");
        form.getBasic().setCapabilitySlug("order-export");
        form.getBackground().setPainPoints(Arrays.asList("手工导出太慢"));
        form.getBackground().setImpactScope("运营团队");

        RequirementForm.Feature feature1 = new RequirementForm.Feature();
        feature1.getUserStory().setAs("运营人员");
        feature1.getUserStory().setWant("一键导出订单");
        feature1.getUserStory().setSo("减少手工操作");
        feature1.setDetails("支持按日期筛选");
        RequirementForm.Feature feature2 = new RequirementForm.Feature();
        feature2.getUserStory().setAs("主管");
        feature2.getUserStory().setWant("查看导出记录");
        feature2.getUserStory().setSo("审计有据");
        form.setFeatures(Arrays.asList(feature1, feature2));

        RequirementForm.AcceptanceCriterion criterion1 = new RequirementForm.AcceptanceCriterion();
        criterion1.setWhen("点击导出按钮");
        criterion1.setThen("生成 csv 并下载");
        RequirementForm.AcceptanceCriterion criterion2 = new RequirementForm.AcceptanceCriterion();
        criterion2.setWhen("导出完成");
        criterion2.setThen("记录出现在导出历史");
        form.setAcceptance(Arrays.asList(criterion1, criterion2));

        form.getConstraints().setTechnical(Arrays.asList("导出上限 10 万行"));
        form.getConstraints().setCompliance(Collections.<String>emptyList());
        return form;
    }
}
