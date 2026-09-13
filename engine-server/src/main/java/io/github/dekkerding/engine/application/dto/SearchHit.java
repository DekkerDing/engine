package io.github.dekkerding.engine.application.dto;

import java.util.List;

/**
 * 混合检索单条命中 —— 语义与全文两路融合后的结果条目（应用层值对象）。
 *
 * <p>【教学注释 · 字段从哪来】
 * <pre>
 * documentId/documentName  库内条目 + 文档仓储回填（spec：命中必须包含文档名）
 * snippet                  块原文（两路共用的展示载体）
 * score                    RRF 融合分（见 SearchApplicationService，排名的函数）
 * vectorScore / textScore  两路原始分（余弦 ∈ [-1,1] / BM25 无界——量纲不同仅供人工判读）
 * source                   命中来源：SEMANTIC / FULLTEXT / BOTH（spec：双路命中标注"两者"）
 * highlight                全文路带 &lt;em&gt; 标记的最佳片段；纯语义命中为 null
 * degraded                 这条向量是否为哈希兜底产物（摄取时引擎降级）
 * </pre>
 *
 * <p>【为什么 score 不是余弦】两路分数分布不可比（design.md D3），融合后统一用 RRF 分数
 * 排序；原始分保留在 vectorScore/textScore 里，前端想展示"相似度 92%"时用 vectorScore。
 *
 * <p>【图片命中的标注透出 · photo-semantic-search】sourceType="image" 的命中额外带
 * subject/description/tags/annotationMocked（图片仓储回填；未拆分 = null 占位，
 * 三态语义同 ImageSummary）。文本命中的标注字段恒为 null——前端按 sourceType 区分渲染。
 */
public class SearchHit {

    /** 命中来源枚举——机器值 + 中文标签一体，前端免维护映射表 */
    public enum Source {
        SEMANTIC("语义"), FULLTEXT("全文"), BOTH("两者");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String documentId;
    private final String documentName;
    private final int chunkIndex;
    private final String snippet;
    private final double score;
    private final Double vectorScore;
    private final Double textScore;
    private final Source source;
    private final String highlight;
    private final boolean degraded;
    /** 模态标注（spec：命中携带模态）："text"=文档块，"image"=照片——前端据此渲染不同卡片 */
    private final String sourceType;

    // ---- 图片命中的语义标注（文本命中恒 null） ----
    private final String subject;
    private final String description;
    private final List<String> tags;
    private final Boolean annotationMocked; // 三态：null=未拆分
    /** 重排分数（交叉编码器对 query×标注文本 的打分）；null=未参与重排（直通/未标注候选） */
    private final Double rerankScore;

    public SearchHit(String documentId, String documentName, int chunkIndex, String snippet,
                     double score, Double vectorScore, Double textScore,
                     Source source, String highlight, boolean degraded) {
        this(documentId, documentName, chunkIndex, snippet, score, vectorScore, textScore,
                source, highlight, degraded, "text");
    }

    public SearchHit(String documentId, String documentName, int chunkIndex, String snippet,
                     double score, Double vectorScore, Double textScore,
                     Source source, String highlight, boolean degraded, String sourceType) {
        this(documentId, documentName, chunkIndex, snippet, score, vectorScore, textScore,
                source, highlight, degraded, sourceType, null, null, null, null);
    }

    public SearchHit(String documentId, String documentName, int chunkIndex, String snippet,
                     double score, Double vectorScore, Double textScore,
                     Source source, String highlight, boolean degraded, String sourceType,
                     String subject, String description, List<String> tags, Boolean annotationMocked) {
        this(documentId, documentName, chunkIndex, snippet, score, vectorScore, textScore,
                source, highlight, degraded, sourceType, subject, description, tags,
                annotationMocked, null);
    }

    public SearchHit(String documentId, String documentName, int chunkIndex, String snippet,
                     double score, Double vectorScore, Double textScore,
                     Source source, String highlight, boolean degraded, String sourceType,
                     String subject, String description, List<String> tags, Boolean annotationMocked,
                     Double rerankScore) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.chunkIndex = chunkIndex;
        this.snippet = snippet;
        this.score = score;
        this.vectorScore = vectorScore;
        this.textScore = textScore;
        this.source = source;
        this.highlight = highlight;
        this.degraded = degraded;
        this.sourceType = sourceType;
        this.subject = subject;
        this.description = description;
        this.tags = tags;
        this.annotationMocked = annotationMocked;
        this.rerankScore = rerankScore;
    }

    public String getDocumentId() { return documentId; }
    public String getDocumentName() { return documentName; }
    public int getChunkIndex() { return chunkIndex; }
    public String getSnippet() { return snippet; }
    public double getScore() { return score; }
    public Double getVectorScore() { return vectorScore; }
    public Double getTextScore() { return textScore; }
    public Source getSource() { return source; }
    public String getHighlight() { return highlight; }
    public boolean isDegraded() { return degraded; }
    public String getSourceType() { return sourceType; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public List<String> getTags() { return tags; }
    public Boolean getAnnotationMocked() { return annotationMocked; }
    public Double getRerankScore() { return rerankScore; }
}
