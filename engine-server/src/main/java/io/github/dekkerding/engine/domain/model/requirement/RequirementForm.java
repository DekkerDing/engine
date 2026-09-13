package io.github.dekkerding.engine.domain.model.requirement;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求表单值对象 —— 业务人员在三步表单里填写的全部内容。
 *
 * <p>【教学注释 · 值对象 vs 实体】表单数据没有身份、可整体替换（保存即新值），
 * 是典型的值对象。它被序列化为 form_data_json 落库（Jackson 仅在 infrastructure
 * 层使用，domain 保持纯 POJO——无任何注解依赖）。
 *
 * <p>三步结构与前端 Steps 一一对应：basic(第一步) → background/features(第二步)
 * → acceptance/constraints(第三步)。
 */
public class RequirementForm {

    private BasicInfo basic = new BasicInfo();
    private Background background = new Background();
    private List<Feature> features = new ArrayList<Feature>();
    private List<AcceptanceCriterion> acceptance = new ArrayList<AcceptanceCriterion>();
    private Constraints constraints = new Constraints();

    /** 空值防御：反序列化缺字段（如暂存第一步就关页面）时给可用的空结构 */
    public void ensureDefaults() {
        if (basic == null) basic = new BasicInfo();
        if (background == null) background = new Background();
        if (features == null) features = new ArrayList<Feature>();
        if (acceptance == null) acceptance = new ArrayList<AcceptanceCriterion>();
        if (constraints == null) constraints = new Constraints();
        for (Feature feature : features) {
            if (feature.userStory == null) feature.userStory = new UserStory();
        }
    }

    // ---------- 嵌套结构（字段名即表单契约，前后端一致） ----------

    /** 第一步：基本信息 */
    public static class BasicInfo {
        private String title = "";
        private String submitter = "";
        private String department = "";
        private String priority = "MEDIUM";
        private String expectDate = "";
        /** OpenSpec 产物 specs/&lt;slug&gt;/ 的目录名；空则由聚合根生成 req-{id短} */
        private String capabilitySlug = "";

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSubmitter() { return submitter; }
        public void setSubmitter(String submitter) { this.submitter = submitter; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getExpectDate() { return expectDate; }
        public void setExpectDate(String expectDate) { this.expectDate = expectDate; }
        public String getCapabilitySlug() { return capabilitySlug; }
        public void setCapabilitySlug(String capabilitySlug) { this.capabilitySlug = capabilitySlug; }
    }

    /** 第二步：业务背景 */
    public static class Background {
        private List<String> painPoints = new ArrayList<String>();
        private String impactScope = "";
        private List<String> metrics = new ArrayList<String>();

        public List<String> getPainPoints() { return painPoints; }
        public void setPainPoints(List<String> painPoints) { this.painPoints = painPoints; }
        public String getImpactScope() { return impactScope; }
        public void setImpactScope(String impactScope) { this.impactScope = impactScope; }
        public List<String> getMetrics() { return metrics; }
        public void setMetrics(List<String> metrics) { this.metrics = metrics; }
    }

    /** 第二步：期望功能（一条 = 一个用户故事） */
    public static class Feature {
        private UserStory userStory = new UserStory();
        private String details = "";

        public UserStory getUserStory() { return userStory; }
        public void setUserStory(UserStory userStory) { this.userStory = userStory; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
    }

    /** 用户故事三要素：作为(as) / 想要(want) / 以便(so) */
    public static class UserStory {
        private String as = "";
        private String want = "";
        private String so = "";

        public String getAs() { return as; }
        public void setAs(String as) { this.as = as; }
        public String getWant() { return want; }
        public void setWant(String want) { this.want = want; }
        public String getSo() { return so; }
        public void setSo(String so) { this.so = so; }
    }

    /** 第三步：验收标准（WHEN/THEN 结构化表述，渲染时原样保留） */
    public static class AcceptanceCriterion {
        private String when = "";
        private String then = "";

        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }
        public String getThen() { return then; }
        public void setThen(String then) { this.then = then; }
    }

    /** 第三步：约束条件 */
    public static class Constraints {
        private List<String> technical = new ArrayList<String>();
        private List<String> compliance = new ArrayList<String>();

        public List<String> getTechnical() { return technical; }
        public void setTechnical(List<String> technical) { this.technical = technical; }
        public List<String> getCompliance() { return compliance; }
        public void setCompliance(List<String> compliance) { this.compliance = compliance; }
    }

    public BasicInfo getBasic() { return basic; }
    public void setBasic(BasicInfo basic) { this.basic = basic; }
    public Background getBackground() { return background; }
    public void setBackground(Background background) { this.background = background; }
    public List<Feature> getFeatures() { return features; }
    public void setFeatures(List<Feature> features) { this.features = features; }
    public List<AcceptanceCriterion> getAcceptance() { return acceptance; }
    public void setAcceptance(List<AcceptanceCriterion> acceptance) { this.acceptance = acceptance; }
    public Constraints getConstraints() { return constraints; }
    public void setConstraints(Constraints constraints) { this.constraints = constraints; }
}
