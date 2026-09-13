package io.github.dekkerding.engine.domain.model.image;

import java.util.Collections;
import java.util.List;

/**
 * 图片结构化标注（值对象）—— 一张照片的"语义身份"。
 *
 * <p>三字段语义（对齐 python/core/annotate.py 的输出契约）：
 * <ul>
 *   <li>subject：短语级主题（如"红色、小花"）</li>
 *   <li>description：自然语言描述（如"红色的小花"）</li>
 *   <li>tags：标签词列表</li>
 * </ul>
 *
 * <p>【mocked 纪律】本期标注由 mock 实现产出，mocked=true 必须显式透传到
 * API 响应与前端（对齐 CLIP degraded 标志的既有纪律：不伪装真实识别）。
 * 真实 VLM 替换后 mocked=false，字段契约不变。
 */
public class ImageAnnotation {

    private final String subject;
    private final String description;
    private final List<String> tags;
    private final boolean mocked;

    public ImageAnnotation(String subject, String description, List<String> tags, boolean mocked) {
        this.subject = subject == null ? "" : subject;
        this.description = description == null ? "" : description;
        this.tags = tags == null ? Collections.<String>emptyList() : Collections.unmodifiableList(tags);
        this.mocked = mocked;
    }

    /** 标注文本的拼接形态（描述路向量化与重排取文本的统一来源："主题。描述"） */
    public String combinedText() {
        if (subject.isEmpty()) {
            return description;
        }
        return description.isEmpty() ? subject : subject + "。" + description;
    }

    /** 是否具备可检索的语义内容（subject/description 均空 = 实质未拆分） */
    public boolean hasContent() {
        return !subject.isEmpty() || !description.isEmpty();
    }

    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public List<String> getTags() { return tags; }
    public boolean isMocked() { return mocked; }
}
