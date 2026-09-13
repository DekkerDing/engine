package io.github.dekkerding.engine.interfaces.rest.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * 检索请求 DTO —— POST /search 的请求体。
 *
 * <p>【教学注释 · Bean Validation 声明式校验】校验规则写在字段上（@NotBlank...），
 * Controller 方法参数标一个 @Valid，框架在进方法体之前完成校验、不合法直接抛
 * MethodArgumentNotValidException → GlobalExceptionHandler 翻译成 400 信封。
 * 对比手工 if-throw：规则与字段声明在同一行、零样板、漏不掉。
 *
 * <p>【坑】Spring Boot 2.6 的 starter-validation 用的是 javax.validation
 * （Boot 3.x 才是 jakarta.validation）——import 错包注解不生效且不报错，静默失效。
 */
public class SearchRequest {

    /** 检索查询（自然语言，中文直接支持）。空/纯空白在入口即被拒绝（spec） */
    @NotBlank(message = "查询不能为空")
    private String query;

    /**
     * 期望返回条数（可选）：不传或 &le;0 用服务端默认（10）；上限 50。
     * 注意 @Min(1) 只约束"传了正值"的语义边界——传 0/负数走默认值的宽容策略
     * 在应用层 clampTopK 里做，这里不拦（拦了前端反而要多写一层兜底）。
     */
    @Max(value = 50, message = "topK 不能超过 50")
    private Integer topK;

    /**
     * 检索模态（可选）：text=混合文本检索（缺省，既有行为）；image=跨模态搜图
     * （查询经 CLIP 文本塔，仅对图片向量检索）。非法值在应用层 normalizeModality 400。
     */
    private String modality;

    /**
     * 重排开关（可选，photo-semantic-search）：null=用服务端配置缺省（启用）；
     * false=本次请求直通召回融合结果；仅对 image 模态生效（文本模态无重排阶段）。
     */
    private Boolean rerank;

    /**
     * 重排候选数（可选）：null=用服务端默认（20）；上限 50（@Max 拦截 + 应用层
     * clamp 双保险）。只截"融合后送重排的候选池"大小，不影响最终 topK。
     */
    @Max(value = 50, message = "rerankCandidates 不能超过 50")
    private Integer rerankCandidates;

    public String getQuery() { return query; }
    public Integer getTopK() { return topK; }
    public String getModality() { return modality; }
    public Boolean getRerank() { return rerank; }
    public Integer getRerankCandidates() { return rerankCandidates; }

    /** 仅供 JSON 反序列化使用（Jackson 需要无参构造 + setter 或字段可见性） */
    public void setQuery(String query) { this.query = query; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public void setModality(String modality) { this.modality = modality; }
    public void setRerank(Boolean rerank) { this.rerank = rerank; }
    public void setRerankCandidates(Integer rerankCandidates) { this.rerankCandidates = rerankCandidates; }
}
