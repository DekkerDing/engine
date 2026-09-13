package io.github.dekkerding.engine.infrastructure.search;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import io.github.dekkerding.engine.domain.service.VectorizationDomainService;
import io.github.dekkerding.engine.infrastructure.go.GoVectorReplica;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 内存向量索引 —— 检索的真正执行者（暴力余弦扫描）。
 *
 * <p>【架构位置】SQLite 是"真相库"（重启不丢），本类是"工作集"（启动时全量预加载，
 * 摄取时增量维护）。检索只打内存不打磁盘——万级块 × 512 维的暴力扫在毫秒级，
 * 这是 MVP 阶段最诚实的方案：先正确、再 measurable、最后才谈 ANN（Faiss/HNSW）。
 *
 * <p>【Go 轨路由（gotoolbox 场景二 · design D4/D5）】{@code engine.go.enabled=true}
 * 时检索优先走 Go 副本的并行扫描（{@link GoVectorReplica}），命中三元组回到本索引
 * 换取完整条目；<b>空结果、回表 miss、通道异常一律降级本地扫描</b>——本地轨是
 * 空间闸门与正确性的最终裁决（错误文案单源），也是关闭态/降级态的兜底。
 *
 * <p>【教学注释 · 为什么按 documentId 分组存】
 * 写入模式是"一个文档一批块"（先清后写幂等），删除模式是"按文档删"。
 * Map&lt;docId, List&lt;entry&gt;&gt; 让两种操作都是 O(该文档块数)，而不是全表扫描。
 *
 * <p>【线程模型】摄取线程写、HTTP 请求线程读。synchronized 方法最简单且在学习规模下
 * 无性能问题（持锁时间 = 微秒级点积）——不提前上 ReadWriteLock，YAGNI。
 * <b>例外</b>：Go 轨调用（stdio 通道，超时上限数十秒）必须在本类锁之外——
 * 持锁调通道会把摄取写路径堵死在检索的网络 IO 上。
 */
public class InMemoryVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorIndex.class);

    /** 索引条目：领域对象 + 预归一化的打分向量（自防御，见 replace 的注释） */
    private static final class IndexedEntry {
        final VectorEntry entry;
        final float[] scoringVector;

        IndexedEntry(VectorEntry entry, float[] scoringVector) {
            this.entry = entry;
            this.scoringVector = scoringVector;
        }
    }

    private final Map<String, List<IndexedEntry>> byDocument = new LinkedHashMap<>();

    /** 条目总数（所有文档的块数之和） */
    private int size;

    /** Go 索引副本（engine.go.enabled=true 时非 null；null = 关闭态，行为与基线逐字一致） */
    private final GoVectorReplica goReplica;

    /** 既有构造（无副本）：关闭态与既有测试的行为保持不变。 */
    public InMemoryVectorIndex() {
        this(null);
    }

    public InMemoryVectorIndex(GoVectorReplica goReplica) {
        this.goReplica = goReplica;
    }

    /**
     * 写入/覆盖一个文档的全部条目（幂等：同 documentId 旧条目整体替换）。
     *
     * <p>【自防御归一化】Python 端 encode(normalize_embeddings=True) 已保证 L2=1，
     * 但内存索引不信任上游契约——加进来时若模长偏离 1 就存一份归一化副本专供打分。
     * 代价：每条目最多一次数组拷贝；收益：分数语义永远是余弦 ∈ [-1,1]，
     * 即使将来某个新模型忘了归一化，检索结果也不会悄悄错。
     */
    public synchronized void replace(String documentId, List<VectorEntry> entries) {
        List<IndexedEntry> indexed = new ArrayList<>(entries.size());
        for (VectorEntry entry : entries) {
            indexed.add(new IndexedEntry(entry, normalizedCopy(entry)));
        }
        List<IndexedEntry> old = byDocument.put(documentId, indexed);
        size += indexed.size() - (old == null ? 0 : old.size());
    }

    /** 删除一个文档的全部条目（文档删除联动清理） */
    public synchronized void remove(String documentId) {
        List<IndexedEntry> removed = byDocument.remove(documentId);
        if (removed != null) {
            size -= removed.size();
        }
    }

    /** 库内是否完全没有条目 */
    public synchronized boolean isEmpty() {
        return byDocument.isEmpty();
    }

    public synchronized int size() {
        return size;
    }

    /**
     * Top-K 检索（三参兼容版）：仅按模态过滤，不限定模型——旧调用与既有测试的行为保持不变。
     *
     * @param sourceType null=全库混检；"text"/"image"=按模态过滤
     */
    public List<VectorHit> search(float[] queryVector, int topK, String sourceType) {
        return search(queryVector, topK, sourceType, null);
    }

    /**
     * Top-K 检索（空间过滤版 · Go 轨路由）：归一化点积（= 余弦）打分，按分数降序。
     *
     * <p>【Go 轨路由】{@code engine.go.enabled=true}（goReplica 在场）时优先把查询
     * 发给 Go 副本的并行扫描；以下三种情况<b>降级本地扫描</b>——本地轨是空间闸门
     * 与正确性的最终裁决（错误文案与关闭态单源同款）：
     * <ol>
     *   <li>通道异常（引擎崩溃/超时）——检索可用性优先于并行加速</li>
     *   <li>Go 返回空结果——空结果区分不了"真空"与"副本未同步"，本地轨重算一遍
     *       才能给出正确的空结果或闸门 400</li>
     *   <li>回表 miss（副本有、主索引没有——同步断裂的理论病态）</li>
     * </ol>
     *
     * <p>【空间闸门 · 从维度闸门升级】原规则只校验"维度相等"，但维度只是空间的弱代理：
     * bge 文本向量与 chinese-clip 图片向量同为 512 维，却分属两个无关的向量空间——
     * 纯维度闸门会放行跨空间比较，打出来的"相似度"是噪音。(sourceType, modelKey)
     * 才是空间的身份证。规则（本地轨裁决，Go 轨空结果同享此判定）：
     * <ol>
     *   <li>只与「(sourceType, modelKey) 匹配 且 维度 == 查询维度」的条目打分
     *       （维度仍是数学硬校验：点积要求同维）</li>
     *   <li>同模态内有条目但模型/维度都不匹配（编码模型切换后旧向量还在）→ 抛 400
     *       明确错误并给出修复指引——空结果 = "没找到"，模型不符 = "不能比"，不能混</li>
     *   <li>目标模态在库内完全无条目（库里只有其它模态）或库为空 → 空结果
     *       （合法状态："图片库还没有图片"，包括删光之后）</li>
     * </ol>
     *
     * <p>【锁纪律】本方法不加锁——Go 轨通道调用（stdio IO，超时上限数十秒）若持锁
     * 会堵死摄取写路径；本地扫描在 {@link #searchLocal} 的 synchronized 内完成。
     *
     * @param sourceType null=不过滤；"text"/"image"=按模态过滤
     * @param modelKey   null=不过滤；"text-embedding-zh"/"clip"=按模型过滤（与 Python registry 键一致）
     */
    public List<VectorHit> search(float[] queryVector, int topK, String sourceType, String modelKey) {
        if (topK <= 0) {
            return Collections.emptyList();
        }
        if (goReplica != null) {
            List<VectorHit> goHits = tryGoSearch(queryVector, topK, sourceType, modelKey);
            if (goHits != null) {
                return goHits;
            }
        }
        return searchLocal(queryVector, topK, sourceType, modelKey);
    }

    /** Go 轨检索（锁外）：成功返回命中（可能为空列表），降级信号 = null。 */
    private List<VectorHit> tryGoSearch(float[] queryVector, int topK, String sourceType, String modelKey) {
        List<GoProtocol.VectorSearchResult.HitItem> hits;
        try {
            hits = goReplica.search(queryVector, topK, sourceType, modelKey);
        } catch (Exception e) {
            log.warn("Go 副本检索失败，降级本地扫描: {}", e.getMessage());
            return null;
        }
        if (hits.isEmpty()) {
            // 空结果语义模糊（真空 vs 副本未同步）——本地轨重算给出裁决
            return null;
        }
        // 回表：Go 只回 (doc_id, chunk_index, score) 三元组，完整条目在主索引
        List<VectorHit> result = new ArrayList<>(hits.size());
        for (GoProtocol.VectorSearchResult.HitItem hit : hits) {
            IndexedEntry found = lookup(hit.document_id, hit.chunk_index);
            if (found == null) {
                log.warn("Go 副本命中在主索引回表 miss（副本与主索引不同步），降级本地扫描: ({},{})",
                        hit.document_id, hit.chunk_index);
                return null;
            }
            result.add(new VectorHit(found.entry, hit.score));
        }
        return result;
    }

    /** 主索引回表：按 (documentId, chunkIndex) 取已索引条目（微秒级持锁）。 */
    private synchronized IndexedEntry lookup(String documentId, int chunkIndex) {
        List<IndexedEntry> entries = byDocument.get(documentId);
        if (entries != null) {
            for (IndexedEntry item : entries) {
                if (item.entry.getChunkIndex() == chunkIndex) {
                    return item;
                }
            }
        }
        return null;
    }

    /** 本地轨：原暴力扫描逻辑（关闭态唯一路径；Go 轨的闸门裁决与兜底）。 */
    private synchronized List<VectorHit> searchLocal(float[] queryVector, int topK, String sourceType, String modelKey) {
        float[] query = normalizedCopyOfVector(queryVector);

        List<VectorHit> spaceHits = new ArrayList<>();
        boolean hasStaleInModality = false;

        for (List<IndexedEntry> entries : byDocument.values()) {
            for (IndexedEntry item : entries) {
                boolean modalityMatch = sourceType == null
                        || sourceType.equals(item.entry.getSourceType());
                boolean spaceMatch = modalityMatch
                        && (modelKey == null || modelKey.equals(item.entry.getModelKey()))
                        && item.entry.getDimension() == query.length;
                if (spaceMatch) {
                    double score = VectorizationDomainService.dot(query, item.scoringVector);
                    spaceHits.add(new VectorHit(item.entry, score));
                } else if (modalityMatch) {
                    // 同模态但模型/维度不匹配——候选为空时用于区分"模型切换"错误场景
                    hasStaleInModality = true;
                }
                // 其它模态的条目：与本次检索无关，直接跳过（目标模态库空是合法的空结果）
            }
        }

        // 报错只留给"同模态内空间不匹配"（用户切换了编码模型，旧向量不可比，需要修复指引）；
        // 目标模态在库内完全没有条目（库里只有其它模态）→ 空结果——"图片库没有图片"
        // 不是错误：删除全部图片后检索图片必须返回空列表而非报错
        if (spaceHits.isEmpty() && hasStaleInModality) {
            throw spaceGateError(sourceType, modelKey, query.length);
        }
        return topK(spaceHits, topK);
    }

    private EngineException spaceGateError(String sourceType, String modelKey, int queryDim) {
        TreeSet<String> spaces = new TreeSet<>();
        for (List<IndexedEntry> entries : byDocument.values()) {
            for (IndexedEntry item : entries) {
                spaces.add(item.entry.getSourceType() + "/" + item.entry.getModelKey()
                        + "(" + item.entry.getDimension() + "维)");
            }
        }
        String target = (sourceType == null ? "*" : sourceType) + "/"
                + (modelKey == null ? "*" : modelKey) + "(" + queryDim + "维)";
        return EngineException.badRequest("查询向量维度 " + queryDim + " 与目标空间 " + target
                + " 的现有向量不可比（库内空间: " + spaces + "）：编码模型切换后旧向量不可比，"
                + "请删除相关资源后用当前模型重新摄取（或切换回原模型）");
    }

    /** 按分数降序取前 K。全排序 O(n log n)：学习规模足够；百万级再换 bounded heap / ANN */
    private List<VectorHit> topK(List<VectorHit> hits, int topK) {
        hits.sort(Comparator.comparingDouble(VectorHit::getScore).reversed());
        return new ArrayList<>(hits.subList(0, Math.min(topK, hits.size())));
    }

    private static float[] normalizedCopy(VectorEntry entry) {
        return normalizedCopyOfVector(entry.getVector());
    }

    /** 模长已是 1（±1e-3 容差）直接复用原数组引用；否则做归一化副本 */
    private static float[] normalizedCopyOfVector(float[] vector) {
        double norm = VectorizationDomainService.norm(vector);
        if (norm == 0) {
            throw EngineException.internal("零向量不可入索引（上游产生了空向量）", null);
        }
        if (Math.abs(norm - 1.0) < 1e-3) {
            return vector;
        }
        float[] copy = Arrays.copyOf(vector, vector.length);
        for (int i = 0; i < copy.length; i++) {
            copy[i] = (float) (copy[i] / norm);
        }
        return copy;
    }
}
