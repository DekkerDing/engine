package io.github.dekkerding.engine.application.dto;

import java.util.Collections;
import java.util.List;

/**
 * 混合检索总响应 —— 一次检索的完整答案（应用层值对象，不可变、可安全缓存共享）。
 *
 * <p>【教学注释 · 为什么整个结果对象可以被缓存共享】
 * 所有字段 final、无 setter、List 用不可变副本包装——同一个实例被多个请求/线程读取
 * 时不可能被篡改。这是"值对象线程安全"的最朴素形态：不可变天然线程安全。
 *
 * <p>【字段契约】（spec vector-search）
 * <pre>
 * query           本次查询原文（回显——前端历史列表与调试都用得上）
 * items           融合排序后的命中列表（可能为空，空 = 合法结果而非错误）
 * total           命中条数（= items.size()；翻页场景下才会与 size 分离）
 * tookMs          服务端检索耗时（含查询向量化 + 两路检索 + 融合；spec：响应含耗时）
 * degraded        引擎降级标志（查询向量是哈希兜底时 true——前端据此挂全局提示）
 * degradedReason  降级原因（引擎最近一次错误；非降级时为 null）
 * cached          本次响应是否来自缓存（观测字段：验证"第二次不再计算"的直观数据）
 * </pre>
 */
public class SearchResult {

    private final String query;
    private final List<SearchHit> items;
    private final int total;
    private final long tookMs;
    private final boolean degraded;
    private final String degradedReason;
    private final boolean cached;
    /** 本次检索的模态（spec：响应携带模态标注）："text"=混合文本检索，"image"=跨模态搜图 */
    private final String modality;
    /** 重排是否实际生效（photo-semantic-search）：true=结果按交叉编码器分排序 */
    private final boolean reranked;
    /** 未重排的原因（观测字段）：关闭 / 候选无标注 / 重排器不可用；重排生效时为 null */
    private final String rerankReason;

    public SearchResult(String query, List<SearchHit> items, long tookMs,
                        boolean degraded, String degradedReason, boolean cached) {
        this(query, items, tookMs, degraded, degradedReason, cached, "text");
    }

    public SearchResult(String query, List<SearchHit> items, long tookMs,
                        boolean degraded, String degradedReason, boolean cached, String modality) {
        this(query, items, tookMs, degraded, degradedReason, cached, modality, false, null);
    }

    public SearchResult(String query, List<SearchHit> items, long tookMs,
                        boolean degraded, String degradedReason, boolean cached, String modality,
                        boolean reranked, String rerankReason) {
        this.query = query;
        this.items = Collections.unmodifiableList(items);
        this.total = items.size();
        this.tookMs = tookMs;
        this.degraded = degraded;
        this.degradedReason = degradedReason;
        this.cached = cached;
        this.modality = modality;
        this.reranked = reranked;
        this.rerankReason = rerankReason;
    }

    public String getQuery() { return query; }
    public List<SearchHit> getItems() { return items; }
    public int getTotal() { return total; }
    public long getTookMs() { return tookMs; }
    public boolean isDegraded() { return degraded; }
    public String getDegradedReason() { return degradedReason; }
    public boolean isCached() { return cached; }
    public String getModality() { return modality; }
    public boolean isReranked() { return reranked; }
    public String getRerankReason() { return rerankReason; }
}
