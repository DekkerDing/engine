package io.github.dekkerding.engine.domain.model.image;

/**
 * 图片摄取状态机 —— 与文档同构的异步模式，但轻量得多。
 *
 * <p>【教学注释 · 为什么不复用 DocumentStatus】文档管线有 PARSING（提文本）和
 * CHUNKING（切语义块）两个阶段；图片"整图一向量"（spec：一图一向量），
 * 没有这两个阶段。硬套文档状态机会出现"图片停在 CHUNKING"这种无意义状态——
 * 状态机的每个状态都该对应一个真实发生的动作。
 *
 * <pre>
 * PENDING → VECTORIZING → COMPLETED
 *    │          │
 *    └──────────┴──────→ FAILED（带原因；不阻塞其他图片）
 * COMPLETED/FAILED → PENDING（重新向量化，回炉）
 * </pre>
 */
public enum ImageStatus {
    PENDING("已上传，等待向量化"),
    VECTORIZING("向量化中（CLIP 编码）"),
    COMPLETED("入库完成，可被文字检索"),
    FAILED("处理失败（见 errorMessage）");

    private final String label;

    ImageStatus(String label) {
        this.label = label;
    }

    /** 状态中文名（前端与文档状态条同构渲染） */
    public String getLabel() {
        return label;
    }

    /** 终态判定：终态图片不再变化，可安全缓存 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /** 合法流转表 */
    public boolean canTransitionTo(ImageStatus next) {
        switch (this) {
            case PENDING:     return next == VECTORIZING || next == FAILED;
            case VECTORIZING: return next == COMPLETED || next == FAILED;
            case COMPLETED:   return next == PENDING;   // 允许重向量化（回炉）
            case FAILED:      return next == PENDING;   // 允许重试
            default:          return false;
        }
    }
}
