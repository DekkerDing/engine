package io.github.dekkerding.engine.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.dekkerding.engine.application.dto.SearchHit;
import io.github.dekkerding.engine.application.dto.SearchResult;
import io.github.dekkerding.engine.application.event.SearchCacheInvalidationEvent;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.resource.TextDocumentResource;
import io.github.dekkerding.engine.domain.model.search.TextHit;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.ImageAssetRepository;
import io.github.dekkerding.engine.domain.repository.EngineStatusQuery;
import io.github.dekkerding.engine.domain.repository.FullTextIndex;
import io.github.dekkerding.engine.domain.repository.RerankProvider;
import io.github.dekkerding.engine.domain.repository.VectorStore;
import io.github.dekkerding.engine.domain.repository.VisionStatusQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 检索应用服务 —— 混合检索用例的编排者（design.md D3）。
 *
 * <p>【用例流程】一次 POST /search 背后发生的事：
 * <pre>
 * ① 校验：空/纯空白查询 → 400（spec：空查询拒绝）
 * ② 查询向量化：把用户查询当"一段文本"送引擎编码（与摄取同一模型，保证同维空间）
 * ③ 双路检索：语义（余弦 top fuse-depth） ∥ 全文（BM25 top fuse-depth）
 * ④ RRF 融合：score(d) = Σ 1/(k + rank_i(d))，k=60——只看排名不看原始分
 * ⑤ 去重（documentId+chunkIndex 同块合源标注）→ 降序 → 截 topK
 * ⑥ 回填文档名 + 降级标志 + 耗时
 * </pre>
 *
 * <p>【教学注释 · 为什么 RRF 而不是分数加权】余弦 ∈ [-1,1]、BM25 无界（可到几十），
 * 两个分布不可比；加权法要先做归一化再调权重，参数不稳。RRF 只用"排名第几"这一
 * 与量纲无关的信息，零调参、对分数分布完全免疫——这是混合检索的业界默认起点。
 *
 * <p>【缓存】相同 (query, topK, modality) 在窗口内直接返回上次结果（spec：第二次不触发底层检索）。
 * SearchResult 不可变，缓存共享同一实例是安全的；{@code engine.cache.search-result.enabled=false}
 * 时完全不建缓存（每次都走完整检索——排查检索问题时关掉它）。
 *
 * <p>【模态路由 · 图片跨模态增量】modality=text（缺省）走上述混合路径，行为与文本 MVP
 * 逐字段一致（既有客户端零改动，spec：向后兼容）；modality=image 走跨模态单路：
 * <pre>
 * 查询文本 ──CLIP 文本塔──▶ 与图片向量同空间 ──▶ 仅对 IMAGE 向量余弦 topK
 * </pre>
 * 图片没有全文路（照片无文本可分词），单路直通不调 Lucene；分数即余弦（无融合）。
 */
@Service
public class SearchApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SearchApplicationService.class);

    /** 融合深度：两路各取多少条进融合池（50 足够小顶 k 的召回，又不会让弱命中淹没强命中） */
    private static final int DEFAULT_FUSE_DEPTH = 50;
    /** RRF 常数 k：越大排名差异对分数影响越平缓，60 是原论文与业界通用值 */
    private static final int DEFAULT_RRF_K = 60;
    /** topK 上限：防止单请求拉爆响应体（与分页 size 上限同一思路） */
    private static final int MAX_TOP_K = 50;
    /** 检索模态参数的合法值（spec：text / image，缺省 text） */
    private static final String MODALITY_TEXT = "text";
    private static final String MODALITY_IMAGE = "image";

    private final EmbeddingProviderRegistry providerRegistry;
    private final EmbeddingProvider textEmbeddingProvider;
    private final VectorStore vectorStore;
    private final FullTextIndex fullTextIndex;
    private final DocumentRepository documentRepository;
    /** 图片命中标注回填 + 描述路候选文本的来源（photo-semantic-search） */
    private final ImageAssetRepository imageAssetRepository;
    private final Optional<EngineStatusQuery> engineStatusQuery;
    private final Optional<VisionStatusQuery> visionStatusQuery;
    /** 重排能力（可选装配）：缺席或不可用 → 检索直通 + reranked=false（spec：重排故障不致检索失败） */
    private final Optional<RerankProvider> rerankProvider;

    private final int defaultTopK;
    private final int fuseDepth;
    private final int rrfK;
    private final double minVectorScore;
    /** 图片模态的余弦阈值：CLIP 实测相关查询 ≈0.32-0.35（远低于 bge 的 0.6+），阈值必须按模态拆分（design D8） */
    private final double imageMinVectorScore;
    /** 描述文本路余弦阈值：与 (text,bge) 同模型但候选是标注短句，分布独立可标定（design D7） */
    private final double imageDescMinVectorScore;
    /** 重排缺省开关与候选池大小（design D7）：候选硬上限 50 对齐 Python 侧 rerank 的 MAX_CANDIDATES */
    private final boolean rerankEnabled;
    private final int rerankCandidates;
    private static final int MAX_RERANK_CANDIDATES = 50;

    /** 缓存实例；enabled=false 时为 null（少一层判空外的对象开销，语义也直白） */
    private final Cache<String, SearchResult> cache;

    public SearchApplicationService(EmbeddingProviderRegistry providerRegistry,
                                    VectorStore vectorStore,
                                    FullTextIndex fullTextIndex,
                                    DocumentRepository documentRepository,
                                    ImageAssetRepository imageAssetRepository,
                                    Optional<EngineStatusQuery> engineStatusQuery,
                                    Optional<VisionStatusQuery> visionStatusQuery,
                                    Optional<RerankProvider> rerankProvider,
                                    @Value("${engine.search.default-top-k:10}") int defaultTopK,
                                    @Value("${engine.search.fuse-depth:50}") int fuseDepth,
                                    @Value("${engine.search.rrf-k:60}") int rrfK,
                                    @Value("${engine.search.min-vector-score:0.40}") double minVectorScore,
                                    @Value("${engine.search.image-min-vector-score:0.25}") double imageMinVectorScore,
                                    @Value("${engine.search.image-desc.min-vector-score:0.40}") double imageDescMinVectorScore,
                                    @Value("${engine.search.rerank.enabled:true}") boolean rerankEnabled,
                                    @Value("${engine.search.rerank.candidates:20}") int rerankCandidates,
                                    @Value("${engine.cache.search-result.enabled:true}") boolean cacheEnabled,
                                    @Value("${engine.cache.search-result.maximum-size:1000}") long cacheMaximumSize,
                                    @Value("${engine.cache.search-result.expire-after-write:5m}") String cacheExpire) {
        // 注册制选 provider（模态查表）——IMAGE 路径的 CLIP provider 也从这里取
        this.providerRegistry = providerRegistry;
        this.textEmbeddingProvider = providerRegistry.require(EmbeddingModality.TEXT);
        this.vectorStore = vectorStore;
        this.fullTextIndex = fullTextIndex;
        this.documentRepository = documentRepository;
        this.imageAssetRepository = imageAssetRepository;
        this.engineStatusQuery = engineStatusQuery;
        this.visionStatusQuery = visionStatusQuery;
        this.rerankProvider = rerankProvider;
        this.defaultTopK = defaultTopK;
        this.fuseDepth = fuseDepth <= 0 ? DEFAULT_FUSE_DEPTH : fuseDepth;
        this.rrfK = rrfK <= 0 ? DEFAULT_RRF_K : rrfK;
        this.minVectorScore = minVectorScore;
        this.imageMinVectorScore = imageMinVectorScore;
        this.imageDescMinVectorScore = imageDescMinVectorScore;
        this.rerankEnabled = rerankEnabled;
        this.rerankCandidates = clampRerankCandidates(rerankCandidates);
        this.cache = cacheEnabled
                ? Caffeine.newBuilder()
                        .maximumSize(cacheMaximumSize)
                        // "5m" 这类人类友好写法 → Duration（@Value 不做自动转换，手动过 DurationStyle）
                        .expireAfterWrite(DurationStyle.detectAndParse(cacheExpire))
                        .build()
                : null;
        log.info("检索缓存: {}（容量 {} / 过写期 {}）",
                cacheEnabled ? "启用" : "关闭", cacheMaximumSize, cacheExpire);
    }

    /**
     * 混合检索入口（既有签名 = 文本模态，行为不变）。
     *
     * @param query 用户自然语言查询（中文直接支持——编码与分词都在各自的通道内处理）
     * @param topK  期望返回条数；&le;0 时用默认值，超上限截断
     * @throws EngineException 400：空/纯空白查询；空间闸门（库内向量与当前模型不对应，见 InMemoryVectorIndex）
     */
    public SearchResult search(String query, Integer topK) {
        return search(query, topK, null, null, null);
    }

    /**
     * 检索入口（模态路由版）。modality 缺省/null = text——与既有文本检索完全一致
     * （spec：既有客户端零改动）；"image" 走双路召回+重排路径。
     *
     * @throws EngineException 400：空/纯空白查询；未知模态值；空间闸门
     */
    public SearchResult search(String query, Integer topK, String modality) {
        return search(query, topK, modality, null, null);
    }

    /**
     * 检索全参入口（photo-semantic-search）。
     *
     * @param rerank             重排开关的逐请求覆盖：null=用服务端配置缺省（启用）；
     *                           仅 image 模态消费（spec：缺省启用、可逐请求关闭）
     * @param rerankCandidates   送重排的候选池大小覆盖：null=用服务端默认
     * @throws EngineException 400：空/纯空白查询；未知模态值；空间闸门
     */
    public SearchResult search(String query, Integer topK, String modality,
                               Boolean rerank, Integer rerankCandidates) {
        if (query == null || query.trim().isEmpty()) {
            throw EngineException.badRequest("查询不能为空");
        }
        String normalizedModality = normalizeModality(modality);
        String trimmed = query.trim();
        int k = clampTopK(topK);
        int candidates = rerankCandidates == null
                ? this.rerankCandidates : clampRerankCandidates(rerankCandidates);
        boolean rerankOn = rerank == null ? this.rerankEnabled : rerank;

        // 缓存命中：返回 cached=true 的浅副本（items 不可变可共享；原对象留在缓存里不动）
        if (cache != null) {
            SearchResult hit = cache.getIfPresent(cacheKey(trimmed, k, normalizedModality, rerankOn, candidates));
            if (hit != null) {
                return new SearchResult(hit.getQuery(), hit.getItems(), hit.getTookMs(),
                        hit.isDegraded(), hit.getDegradedReason(), true, hit.getModality(),
                        hit.isReranked(), hit.getRerankReason());
            }
        }

        long startNanos = System.nanoTime();
        SearchResult result = MODALITY_IMAGE.equals(normalizedModality)
                ? doImageSearch(trimmed, k, rerankOn, candidates, startNanos)
                : doSearch(trimmed, k, startNanos);
        if (cache != null) {
            cache.put(cacheKey(trimmed, k, normalizedModality, rerankOn, candidates), result);
        }
        return result;
    }

    /** 模态参数归一化：null/空 = text（缺省）；未知值 400（spec 只认 text/image） */
    private String normalizeModality(String modality) {
        if (modality == null || modality.trim().isEmpty()) {
            return MODALITY_TEXT;
        }
        String normalized = modality.trim().toLowerCase();
        if (!MODALITY_TEXT.equals(normalized) && !MODALITY_IMAGE.equals(normalized)) {
            throw EngineException.badRequest(
                    "未知检索模态: " + modality + "（支持: text / image）");
        }
        return normalized;
    }

    // ---------- 检索主流程 ----------

    /** startNanos 由入口记下：耗时必须覆盖向量化 + 双路检索 + 融合全程（spec：响应含耗时） */
    private SearchResult doSearch(String query, int topK, long startNanos) {
        // ① 查询向量化：单条文本走与摄取同一 provider——同模型同维度，余弦才有意义
        List<Embedding> embedded = textEmbeddingProvider.embedBatch(
                Collections.singletonList(new TextDocumentResource("query", 0, query)));
        Embedding queryEmbedding = embedded.get(0);

        // ② 双路检索。两路独立失败模式：语义路可能触发维度闸门（400 带指引），
        //    全文路对空索引返回空列表——都符合各自端口的契约
        List<VectorHit> vectorHits;
        try {
            vectorHits = vectorStore.search(queryEmbedding.getVector(), fuseDepth, "text",
                    textEmbeddingProvider.modelKey());
        } catch (EngineException e) {
            // 空间闸门等用户可理解的错误原样上抛（信息里已带"怎么办"）
            throw e;
        }
        // 相似度阈值：余弦暴力扫没有"不相关"概念（永远能排出最近邻），不设下限则
        // "查询一个库里完全不存在概念的词"也会返回勉强最近的块——spec 要求那种情况返回空列表。
        // 0.40 来自实测分布（7.1 手测）：bge-small-zh 对相关查询 ≈0.6+、对完全无关查询
        // 也常打出 0.31 左右的虚高余弦——0.30 拦不住"霍格沃茨"式查询，0.40 居中且两侧
        // 各有一倍边际；换模型分布不同，按需调整此配置。
        vectorHits = dropBelow(vectorHits, minVectorScore);
        List<TextHit> textHits = fullTextIndex.search(query, fuseDepth);

        // ③ RRF 融合 + 去重 + 文档名回填（spec：命中必须含文档名；文档删除是联动清理的，找不到属防御分支）
        List<SearchHit> fused = fuse(vectorHits, textHits, topK, documentNames());

        boolean degraded = queryEmbedding.isDegraded() || engineCurrentlyDegraded();
        String degradedReason = degraded ? currentDegradedReason() : null;
        int tookMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);

        log.info("检索完成: \"{}\" → 语义 {} 条 / 全文 {} 条 / 融合后 {} 条, {}ms, degraded={}",
                query, vectorHits.size(), textHits.size(), fused.size(), tookMs, degraded);
        return new SearchResult(query, fused, tookMs, degraded, degradedReason, false);
    }

    /**
     * 跨模态搜图主流程（modality=image，photo-semantic-search 升级为双路召回+重排）：
     * <pre>
     * ① 像素路：查询文本经 CLIP 文本塔编码 → (image, clip) 空间余弦 top fuseDepth
     * ② 描述路：查询经 bge 编码 → (image, text-embedding) 空间余弦 top fuseDepth
     *    （该空间只存"有标注图片"的描述向量——存量无标注库天然退化为单路，spec）
     * ③ 融合：双路均非空 → RRF(k=60) 按 documentId 合并；单路 → 直通（分数即余弦，
     *    与既有行为逐字段一致）；双路皆空 → 空结果（合法而非错误）
     * ④ 重排：融合序前 candidatesN 条中有标注者送交叉编码器（query × 标注文本），
     *    按重排分降序取 topK；无标注候选按融合序殿后（行为确定）；任何重排侧
     *    故障 → 直通 + reranked=false + 原因（spec：重排故障不致检索失败）
     * ⑤ 命中直出（文件名已随向量冗余）+ 标注字段回填 + rerankScore
     * </pre>
     * 两路编码串行（复用同一 Python 进程，CPU 并行无益——design D4）。
     */
    private SearchResult doImageSearch(String query, int topK,
                                       boolean rerankOn, int candidatesN, long startNanos) {
        // ① 像素路：CLIP 文本塔编码查询（ClipEmbeddingProvider 把非 image 资源分桶到文本塔）
        EmbeddingProvider clipProvider = providerRegistry.require(EmbeddingModality.CROSS);
        Embedding clipQuery = clipProvider.embedBatch(
                Collections.singletonList(new TextDocumentResource("query", 0, query))).get(0);
        List<VectorHit> pixelHits = dropBelow(vectorStore.search(
                clipQuery.getVector(), fuseDepth, "image", clipProvider.modelKey()), imageMinVectorScore);

        // ② 描述路：bge 编码 → (image, 文本模型) 空间。任一环节失败降级为空路（单路
        //    直通语义），不让"可选路"拖垮主检索——但引擎级错误要留痕（warn）
        List<VectorHit> descriptionHits = Collections.emptyList();
        try {
            Embedding textQuery = textEmbeddingProvider.embedBatch(
                    Collections.singletonList(new TextDocumentResource("query", 0, query))).get(0);
            descriptionHits = dropBelow(vectorStore.search(
                    textQuery.getVector(), fuseDepth, "image", textEmbeddingProvider.modelKey()),
                    imageDescMinVectorScore);
        } catch (Exception e) {
            log.warn("图片检索描述路失败，退化为像素路单路: {}", e.getMessage());
        }

        // ③ 融合（双路→RRF；单路→直通保持余弦分，与旧行为逐字段一致）
        Map<String, ImageAsset> assets = imageAnnotations();
        List<ImageCandidate> ordered = fuseImage(pixelHits, descriptionHits);

        // ④ 重排阶段（spec：召回后重排）
        List<ImageCandidate> finalOrder;
        boolean reranked = false;
        String rerankReason = null;
        if (!rerankOn) {
            rerankReason = "重排已关闭";
            finalOrder = cut(ordered, topK);
        } else {
            // 候选池 = 融合序前 candidatesN 条；按"有标注文本"划分两档
            List<ImageCandidate> pool = cut(ordered, candidatesN);
            List<ImageCandidate> annotated = new ArrayList<>();
            List<ImageCandidate> plain = new ArrayList<>();
            for (ImageCandidate c : pool) {
                ImageAsset asset = assets.get(c.documentId);
                if (asset != null && asset.isAnnotated()) {
                    c.annotationText = asset.annotationText();
                    annotated.add(c);
                } else {
                    plain.add(c);
                }
            }
            if (annotated.isEmpty()) {
                rerankReason = "候选均无标注文本，跳过重排";
                finalOrder = cut(ordered, topK);
            } else if (!rerankProvider.isPresent()) {
                rerankReason = "未装配重排器，直通";
                finalOrder = cut(ordered, topK);
            } else {
                try {
                    List<String> texts = new ArrayList<>(annotated.size());
                    for (ImageCandidate c : annotated) {
                        texts.add(c.annotationText);
                    }
                    float[] scores = rerankProvider.get().rerank(query, texts);
                    if (scores.length != annotated.size()) {
                        throw EngineException.downstream("重排器返回分数数与候选不符（期望 "
                                + annotated.size() + " 实得 " + scores.length + "）", null);
                    }
                    for (int i = 0; i < annotated.size(); i++) {
                        annotated.get(i).rerankScore = (double) scores[i];
                    }
                    // 重排分降序（稳定：同分保持融合序）；未标注候选按融合序殿后（design D4）
                    annotated.sort(Comparator.comparingDouble(
                            (ImageCandidate c) -> c.rerankScore == null ? Double.NEGATIVE_INFINITY : c.rerankScore)
                            .reversed());
                    List<ImageCandidate> combined = new ArrayList<>(annotated);
                    combined.addAll(plain);
                    finalOrder = cut(combined, topK);
                    reranked = true;
                } catch (Exception e) {
                    // spec：重排器不可用/报错 → 直通 + 原因，检索不失败
                    rerankReason = "重排器不可用，直通: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    finalOrder = cut(ordered, topK);
                    log.warn("图片检索重排失败（直通）: {}", rerankReason);
                }
            }
        }

        // ⑤ 物化命中：标注回填 + rerankScore；单路直通时 score=余弦（沿旧契约），融合时 score=RRF 分
        List<SearchHit> items = new ArrayList<>(finalOrder.size());
        for (ImageCandidate c : finalOrder) {
            ImageAsset asset = assets.get(c.documentId);
            items.add(c.toHit(asset));
        }

        boolean degraded = clipQuery.isDegraded() || visionCurrentlyDegraded();
        String degradedReason = degraded ? currentVisionDegradedReason() : null;
        int tookMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);

        log.info("图片检索完成: \"{}\" → 像素路 {} 条 / 描述路 {} 条 / 融合 {} 条, {}ms, degraded={}, reranked={}",
                query, pixelHits.size(), descriptionHits.size(), items.size(), tookMs, degraded, reranked);
        return new SearchResult(query, items, tookMs, degraded, degradedReason, false,
                MODALITY_IMAGE, reranked, rerankReason);
    }

    /**
     * 图片双路融合：两路命中按 documentId 合并（图片无分块，chunkIndex 恒 0）。
     * 单路输入 → 直通（分数 = 余弦，与旧行为一致）；双路 → RRF（复用文档混合检索
     * 的常数与语义：只用序位、量纲免疫）。输出按最终分降序。
     */
    private List<ImageCandidate> fuseImage(List<VectorHit> pixelHits, List<VectorHit> descriptionHits) {
        Map<String, ImageCandidate> acc = new LinkedHashMap<>();
        for (int rank = 0; rank < pixelHits.size(); rank++) {
            VectorHit hit = pixelHits.get(rank);
            ImageCandidate c = acc.computeIfAbsent(hit.getEntry().getDocumentId(), k -> new ImageCandidate());
            c.rrfScore += rrfContribution(rank);
            c.pixelScore = hit.getScore();
            c.fillFrom(hit, true);
        }
        for (int rank = 0; rank < descriptionHits.size(); rank++) {
            VectorHit hit = descriptionHits.get(rank);
            ImageCandidate c = acc.computeIfAbsent(hit.getEntry().getDocumentId(), k -> new ImageCandidate());
            c.rrfScore += rrfContribution(rank);
            c.descScore = hit.getScore();
            c.fillFrom(hit, false);
        }

        boolean bothPaths = !pixelHits.isEmpty() && !descriptionHits.isEmpty();
        List<ImageCandidate> ordered = new ArrayList<>(acc.values());
        for (ImageCandidate c : ordered) {
            // 单路直通：分数即该路余弦（旧契约：score = vectorScore）；双路：RRF 分
            c.finalScore = bothPaths ? c.rrfScore
                    : (c.pixelScore != null ? c.pixelScore : c.descScore);
        }
        ordered.sort(Comparator.comparingDouble((ImageCandidate c) -> c.finalScore).reversed());
        return ordered;
    }

    /** 列表截断（不越界） */
    private static <T> List<T> cut(List<T> list, int limit) {
        return new ArrayList<>(list.subList(0, Math.min(limit, list.size())));
    }

    /** 重排候选数夹取：&le;0 用默认 20，超硬上限压到 50（与 Python 侧 MAX_CANDIDATES 对齐） */
    private static int clampRerankCandidates(int candidates) {
        if (candidates <= 0) {
            return 20;
        }
        return Math.min(candidates, MAX_RERANK_CANDIDATES);
    }

    /**
     * 图片候选累加器 —— 一张图片在两路中的分数与元数据的暂存结构（fuseImage 内外传递）。
     * 物化成 SearchHit 前可附加 annotationText/rerankScore（重排阶段写入）。
     */
    private static final class ImageCandidate {
        double rrfScore;
        double finalScore;          // 单路=余弦 / 双路=RRF（物化时的 score）
        Double pixelScore;          // CLIP 像素路余弦（无则 null）
        Double descScore;           // 描述路余弦（无则 null）
        String documentId;
        String filename;
        boolean degraded;
        boolean pixel;
        boolean desc;
        String annotationText;      // 重排阶段回填（候选文本来源）
        Double rerankScore;         // 重排阶段写入（null=未参与）

        void fillFrom(VectorHit hit, boolean pixelPath) {
            this.documentId = hit.getEntry().getDocumentId();
            this.filename = hit.getEntry().getText();
            this.degraded |= hit.getEntry().isDegraded();
            if (pixelPath) {
                this.pixel = true;
            } else {
                this.desc = true;
            }
        }

        SearchHit toHit(ImageAsset asset) {
            // 图片模态的两路（像素/描述）都是语义路：单路 = SEMANTIC，双路 = BOTH
            //（FULLTEXT 留给文本模态的 BM25 路——照片没有可分词的全文）
            SearchHit.Source source = pixel && desc ? SearchHit.Source.BOTH : SearchHit.Source.SEMANTIC;
            return new SearchHit(documentId, filename, 0, filename,
                    finalScore, pixelScore, descScore, source, null, degraded, "image",
                    asset == null ? null : asset.getSubject(),
                    asset == null ? null : asset.getDescription(),
                    asset == null ? null : asset.getTags(),
                    asset == null ? null : asset.getAnnotationMocked(),
                    rerankScore);
        }
    }

    /**
     * RRF 融合主体：两路命中按 (documentId, chunkIndex) 合并、累加排名分、标注来源。
     *
     * <p>【教学注释 · 累加式的含义】一个块在语义路排第 3、全文路排第 1：
     * {@code score = 1/(60+3) + 1/(60+1) = 0.0328}——双路都认的块天然比任何单路冠军
     * （单路最高 ≈ 1/61 = 0.0164）分高，"两路同时命中排名靠前"由此自动成立（spec 场景）。
     */
    private List<SearchHit> fuse(List<VectorHit> vectorHits, List<TextHit> textHits,
                                 int topK, Map<String, String> documentNames) {
        Map<String, FusionAccumulator> acc = new LinkedHashMap<>();
        for (int rank = 0; rank < vectorHits.size(); rank++) {
            VectorHit hit = vectorHits.get(rank);
            FusionAccumulator slot = acc.computeIfAbsent(
                    key(hit.getEntry().getDocumentId(), hit.getEntry().getChunkIndex()),
                    k1 -> new FusionAccumulator());
            slot.rrfScore += rrfContribution(rank);
            slot.vectorScore = hit.getScore();
            slot.vectorEntry = hit.getEntry();
            slot.semantic = true;
        }
        for (int rank = 0; rank < textHits.size(); rank++) {
            TextHit hit = textHits.get(rank);
            FusionAccumulator slot = acc.computeIfAbsent(key(hit.getDocumentId(), hit.getChunkIndex()),
                    k1 -> new FusionAccumulator());
            slot.rrfScore += rrfContribution(rank);
            slot.textScore = hit.getScore();
            if (slot.vectorEntry == null) {
                // 纯全文命中没有向量条目——用 TextHit 补齐展示所需字段
                slot.fullTextOnly = hit;
            }
            slot.highlight = hit.getHighlight();
            slot.fullText = true;
        }

        List<SearchHit> merged = new ArrayList<>(acc.size());
        for (Map.Entry<String, FusionAccumulator> e : acc.entrySet()) {
            FusionAccumulator slot = e.getValue();
            String documentId = slot.vectorEntry != null ? slot.vectorEntry.getDocumentId()
                    : slot.fullTextOnly.getDocumentId();
            merged.add(slot.toHit(documentNames.getOrDefault(documentId, "未知文档")));
        }
        merged.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
        return dedupAndCut(merged, topK);
    }

    /**
     * 重复块去重 + 截断（spec：文档内或跨文档的重复块 SHALL 去重）。
     *
     * <p>同文档同块在累加器阶段已按 key 合并；这里兜的是<b>跨文档</b>重复——
     * 同一文件被上传两次（两个文档 ID、内容相同的块），或不同文档互相转载同一段
     * 落。判定键用片段文本本身：已按 RRF 分降序遍历，首个见到的即分最高者，
     * 后续内容相同的一律丢弃——用户在结果页看到的是"去重后的多元清单"而非复读。
     */
    private List<SearchHit> dedupAndCut(List<SearchHit> sorted, int topK) {
        List<SearchHit> deduped = new ArrayList<>(sorted.size());
        Set<String> seenSnippet = new HashSet<>();
        for (SearchHit hit : sorted) {
            String identity = hit.getSnippet() == null ? "" : hit.getSnippet().trim();
            if (seenSnippet.add(identity)) {
                deduped.add(hit);
            }
        }
        return new ArrayList<>(deduped.subList(0, Math.min(topK, deduped.size())));
    }

    /** 单路排名 → RRF 贡献分（rank 从 0 起，公式里的 rank 从 1 起，故 +1） */
    private double rrfContribution(int zeroBasedRank) {
        return 1.0 / (rrfK + zeroBasedRank + 1);
    }

    /**
     * 摄取数据变更（新文档入库 / 文档删除）→ 缓存全量失效。
     * 窗口只有 5 分钟且容量有限，全清的代价（下次查询重新算一次）远小于
     * 选择性失效的复杂度（要按命中文档反查缓存键）；正确性优先。
     */
    @EventListener
    public void onIngestionChange(SearchCacheInvalidationEvent event) {
        if (cache != null) {
            cache.invalidateAll();
            log.info("检索缓存已失效（文档{}）: {}",
                    event.getChangeType() == SearchCacheInvalidationEvent.ChangeType.DELETED ? "删除" : "入库",
                    event.getDocumentId());
        }
    }

    /** 丢弃余弦分数低于阈值的命中（输入已按分数降序，找到第一个低于阈值的即可整体截断） */
    private List<VectorHit> dropBelow(List<VectorHit> hits, double minScore) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).getScore() < minScore) {
                return hits.subList(0, i);
            }
        }
        return hits;
    }

    private String key(String documentId, int chunkIndex) {
        return documentId + ":" + chunkIndex;
    }

    /** 文档 ID → 文件名映射（学习规模全量拉一次最简单；规模上来换批量查询接口） */
    private Map<String, String> documentNames() {
        Map<String, String> names = new HashMap<>();
        for (Document document : documentRepository.findAll()) {
            names.put(document.getId(), document.getFilename());
        }
        return names;
    }

    /** 图片 ID → 资产映射（命中标注回填用；与 documentNames 同款的全量拉取策略） */
    private Map<String, ImageAsset> imageAnnotations() {
        Map<String, ImageAsset> assets = new HashMap<>();
        for (ImageAsset imageAsset : imageAssetRepository.findAll()) {
            assets.put(imageAsset.getId(), imageAsset);
        }
        return assets;
    }

    private boolean engineCurrentlyDegraded() {
        return engineStatusQuery.isPresent() && engineStatusQuery.get().status().isDegraded();
    }

    private String currentDegradedReason() {
        return engineStatusQuery.isPresent() && engineStatusQuery.get().status().getLastError() != null
                ? "引擎降级运行: " + engineStatusQuery.get().status().getLastError()
                : "引擎处于降级模式（哈希兜底向量），语义精度受限";
    }

    private boolean visionCurrentlyDegraded() {
        return visionStatusQuery.isPresent() && visionStatusQuery.get().visionStatus().isDegraded();
    }

    private String currentVisionDegradedReason() {
        return visionStatusQuery.isPresent() && visionStatusQuery.get().visionStatus().getLastError() != null
                ? "CLIP 引擎降级运行: " + visionStatusQuery.get().visionStatus().getLastError()
                : "CLIP 处于降级模式（哈希兜底向量），跨模态语义精度受限";
    }

    private int clampTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return defaultTopK;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    /** 缓存键必须含模态与重排参数：同词的不同检索口径是不同答案，不能互相命中 */
    private String cacheKey(String query, int topK, String modality, boolean rerankOn, int candidates) {
        return modality + "|" + query + "|" + topK + "|rr=" + rerankOn + "|rc=" + candidates;
    }

    /**
     * 融合累加器 —— 一个块在两路中的分数与来源的暂存结构（fuse 方法内部用完即弃）。
     * 不用 Map&lt;String, double[]&gt; 之类数组戏法：字段有名字，可读性优先。
     */
    private static final class FusionAccumulator {
        double rrfScore;
        Double vectorScore;
        Double textScore;
        io.github.dekkerding.engine.domain.model.vector.VectorEntry vectorEntry;
        TextHit fullTextOnly;
        String highlight;
        boolean semantic;
        boolean fullText;

        SearchHit toHit(String documentName) {
            int chunkIndex = vectorEntry != null ? vectorEntry.getChunkIndex()
                    : fullTextOnly.getChunkIndex();
            String snippet = vectorEntry != null ? vectorEntry.getText()
                    : fullTextOnly.getText();
            boolean degraded = vectorEntry != null && vectorEntry.isDegraded();
            SearchHit.Source source = semantic && fullText ? SearchHit.Source.BOTH
                    : semantic ? SearchHit.Source.SEMANTIC
                    : SearchHit.Source.FULLTEXT;
            String documentId = vectorEntry != null ? vectorEntry.getDocumentId()
                    : fullTextOnly.getDocumentId();
            return new SearchHit(documentId, documentName, chunkIndex, snippet,
                    rrfScore, vectorScore, textScore, source, highlight, degraded);
        }
    }
}
