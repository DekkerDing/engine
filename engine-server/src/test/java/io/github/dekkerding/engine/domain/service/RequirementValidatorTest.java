package io.github.dekkerding.engine.domain.service;

import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementIR;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.github.dekkerding.engine.domain.service.RequirementTestFixtures.fullForm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整性闸门测试 —— 任务 1.3 验收：通过 / 各缺失项清单场景。
 * 纯函数直测，零替身。
 */
class RequirementValidatorTest {

    private final RequirementValidator validator = new RequirementValidator();

    @Test
    void 完整需求通过闸门() {
        List<String> missing = validator.validate(irOf(fullForm()));
        assertTrue(missing.isEmpty(), "完整需求不应有缺失项: " + missing);
    }

    @Test
    void 缺验收标准被拦截并给出清单() {
        RequirementForm form = fullForm();
        form.setAcceptance(Collections.<RequirementForm.AcceptanceCriterion>emptyList());
        List<String> missing = validator.validate(irOf(form));
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("验收标准至少一条"));
    }

    @Test
    void 缺标题与缺功能同时报告() {
        RequirementForm form = fullForm();
        form.getBasic().setTitle("   ");
        form.setFeatures(Collections.<RequirementForm.Feature>emptyList());
        List<String> missing = validator.validate(irOf(form));
        assertEquals(2, missing.size());
        assertTrue(missing.get(0).contains("标题"));
        assertTrue(missing.get(1).contains("期望功能至少一条"));
    }

    @Test
    void 用户故事三要素不齐备逐条报告() {
        RequirementForm form = fullForm();
        form.getFeatures().get(0).getUserStory().setSo("");
        form.getFeatures().get(1).getUserStory().setWant("");
        List<String> missing = validator.validate(irOf(form));
        assertEquals(2, missing.size());
        assertTrue(missing.get(0).contains("第 1 条用户故事"));
        assertTrue(missing.get(1).contains("第 2 条用户故事"));
    }

    @Test
    void 验收标准_when_then不完整被报告() {
        RequirementForm form = fullForm();
        form.getAcceptance().get(0).setThen("");
        List<String> missing = validator.validate(irOf(form));
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("第 1 条验收标准"));
    }

    // ---------- 造数 ----------

    private RequirementIR irOf(RequirementForm form) {
        String id = UUID.randomUUID().toString();
        RequirementSubmission submission = new RequirementSubmission(id, form, Instant.now());
        return RequirementIR.of(submission, Arrays.<Attachment>asList());
    }
}

