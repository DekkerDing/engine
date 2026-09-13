package io.github.dekkerding.engine.domain.model.document;

/**
 * 文档向量化状态机 —— 文档生命周期只有一个当前状态，流转方向固定。
 *
 * <p>【教学注释 · 状态机文档化】把合法流转写进代码而不是注释，越界的流转
 * （比如 COMPLETED → VECTORIZING）在转换方法里直接拒绝——错误状态不再悄悄产生。
 *
 * <pre>
 * PENDING → PARSING → CHUNKING → VECTORIZING → COMPLETED
 *    │         │          │            │
 *    └─────────┴──────────┴────────────┴──────→ FAILED（任何阶段可失败，带原因）
 * </pre>
 */
public enum DocumentStatus {
    PENDING("已上传，等待处理"),
    PARSING("解析中（提取文本）"),
    CHUNKING("分块中（切分语义段）"),
    VECTORIZING("向量化中（Python 引擎编码）"),
    COMPLETED("全部完成，可检索"),
    FAILED("处理失败（见 errorMessage）");

    private final String label;

    DocumentStatus(String label) {
        this.label = label;
    }

    /** 状态中文名（前端 ProgressTimeline 直接展示） */
    public String getLabel() {
        return label;
    }

    /** 终态判定：终态文档不再变化，可安全缓存 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /** 合法流转表：当前状态 → 允许进入的下一个状态集合 */
    public boolean canTransitionTo(DocumentStatus next) {
        switch (this) {
            case PENDING:      return next == PARSING || next == FAILED;
            case PARSING:      return next == CHUNKING || next == FAILED;
            case CHUNKING:     return next == VECTORIZING || next == FAILED;
            case VECTORIZING:  return next == COMPLETED || next == FAILED;
            case COMPLETED:    return next == PENDING;   // 允许重向量化（回炉）
            case FAILED:       return next == PENDING;   // 允许重试
            default:           return false;
        }
    }
}
