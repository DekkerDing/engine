package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.SearchHit;
import io.github.dekkerding.engine.application.dto.SearchResult;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.model.engine.EngineStatus;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.VisionStatusQuery;
import io.github.dekkerding.engine.infrastructure.document.CompositeDocumentParser;
import io.github.dekkerding.engine.infrastructure.document.TxtDocumentParser;
import io.github.dekkerding.engine.infrastructure.fulltext.LuceneFullTextIndex;
import io.github.dekkerding.engine.infrastructure.persistence.DatabaseMigrator;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteDocumentRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteImageAssetRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 检索模态路由测试 —— 任务 4.1/4.2 的验收：
 * 混合库（bge 文本向量 + CLIP 图片向量，同 4 维不同空间）下——
 * 「红塔」文本经 image 模态命中已摄取照片且分数降序；缺省模态与文本 MVP 逐字段一致。
 *
 * <p>【混合库构造】文档走真实摄取管线（DocumentApplicationService），
 * 图片向量直接 vectorStore.save——模拟"已摄取完成的照片"（摄取管线自身由
 * ImageApplicationServiceTest 验收，这里聚焦检索路由）。
 */
class SearchModalityRoutingTest {

    @TempDir
    Path tempDir;

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** bge 文本替身：文本长度 %4 分桶（与 DedupTest 同款） */
    private static class StubTextProvider implements EmbeddingProvider {
        @Override public EmbeddingModality modality() { return EmbeddingModality.TEXT; }
        @Override public String modelKey() { return "stub-text-model"; }
        @Override public int dimension() { return 4; }
        @Override public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            // 【空间键纪律】Embedding.modelKey 必须与 modelKey() 一致——检索侧按它过滤空间
            return embed(resources, "stub-text-model", false);
        }
    }

    /** CLIP 替身：degradable 可控 */
    private static class StubClipProvider implements EmbeddingProvider {
        boolean degraded;

        @Override public EmbeddingModality modality() { return EmbeddingModality.CROSS; }
        @Override public String modelKey() { return "stub-clip"; }
        @Override public int dimension() { return 4; }
        @Override public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            return embed(resources, "stub-clip", degraded);
        }
    }

    /** 重排替身：fail=模拟重排器不可用；分数按候选文本中的标记数字生成（测试可控序） */
    private static class StubRerankProvider implements io.github.dekkerding.engine.domain.repository.RerankProvider {
        boolean fail;
        int invocations;
        String capturedQuery;
        List<String> capturedCandidates;

        @Override
        public float[] rerank(String query, List<String> candidates) {
            invocations++;
            capturedQuery = query;
            capturedCandidates = new ArrayList<>(candidates);
            if (fail) {
                throw EngineException.downstream("reranker 槽位不可用（模拟重排失败）", null);
            }
            // 候选文本形如 "…#R0.9" → 分数取标记值；无标记按内容长度散列（稳定可重复）
            float[] scores = new float[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                String text = candidates.get(i);
                int marker = text.lastIndexOf("#R");
                scores[i] = marker >= 0
                        ? Float.parseFloat(text.substring(marker + 2))
                        : (text.hashCode() % 100) / 100f;
            }
            return scores;
        }
    }

    private static List<Embedding> embed(List<? extends VectorableResource> resources,
                                         String modelKey, boolean degraded) {
        List<Embedding> result = new ArrayList<>();
        for (VectorableResource r : resources) {
            float[] vector = new float[4];
            vector[r.asText().length() % 4] = 1f;
            result.add(new Embedding(vector, modelKey, degraded));
        }
        return result;
    }

    private SqliteConnectionManager connectionManager;
    private SqliteVectorStore vectorStore;
    private LuceneFullTextIndex fullTextIndex;
    private DocumentRepository documentRepository;
    private io.github.dekkerding.engine.domain.repository.ImageAssetRepository imageAssetRepository;
    private StubClipProvider clipProvider;
    private StubRerankProvider reranker;
    private DocumentApplicationService ingestion;
    private SearchApplicationService search;

    @BeforeEach
    void 装配() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate();
        documentRepository = new SqliteDocumentRepository(connectionManager);
        imageAssetRepository = new SqliteImageAssetRepository(connectionManager);
        vectorStore = new SqliteVectorStore(connectionManager);
        vectorStore.preload();
        fullTextIndex = new LuceneFullTextIndex(tempDir.resolve("lucene").toString());

        StubTextProvider textProvider = new StubTextProvider();
        clipProvider = new StubClipProvider();
        reranker = new StubRerankProvider();
        VisionStatusQuery visionStatus = () -> new EngineStatus(
                true, clipProvider.degraded, "stub-channel",
                Arrays.asList("stub-clip"), 4,
                clipProvider.degraded ? "真实模型加载失败，哈希兜底" : null);

        ingestion = new DocumentApplicationService(
                documentRepository,
                new CompositeDocumentParser(Arrays.asList(new TxtDocumentParser())),
                vectorStore,
                fullTextIndex,
                Arrays.asList(textProvider),
                () -> new EngineStatus(true, false, "stub-channel",
                        Arrays.asList("stub-text-model"), 4, null),
                tempDir.resolve("documents").toString(),
                50 * 1024 * 1024, 400, 1,
                event -> { });

        // 缓存关掉：模态断言要每次都走真实检索；重排缺省启用（4.2/4.3 用例依赖）
        search = new SearchApplicationService(
                new EmbeddingProviderRegistry(Arrays.asList(textProvider, clipProvider)),
                vectorStore,
                fullTextIndex,
                documentRepository,
                imageAssetRepository,
                Optional.empty(),
                Optional.of(visionStatus),
                Optional.of(reranker),
                10, 50, 60, 0.0, 0.25,
                0.40, true, 20,
                false, 1000, "5m");
    }

    @AfterEach
    void 收尾() {
        ingestion.shutdown();
        fullTextIndex.shutdown();
    }

    // ---------- 混合库脚手架 ----------

    /** 模拟一张已摄取的照片：一条 image 空间向量（文件名冗余入库） */
    private void ingestPhoto(String imageId, String fileName, float[] vector) {
        vectorStore.save(imageId, Collections.singletonList(
                new VectorEntry(imageId, "image", 0, fileName, "stub-clip", false, vector, NOW)));
    }

    /** 模拟一张"带标注"的已摄取照片：image_asset 行（含标注字段）+ 一条 CLIP 向量 */
    private void ingestAnnotatedPhoto(String imageId, String fileName, float[] vector,
                                      io.github.dekkerding.engine.domain.model.image.ImageAnnotation annotation) {
        io.github.dekkerding.engine.domain.model.image.ImageAsset asset =
                new io.github.dekkerding.engine.domain.model.image.ImageAsset(
                        imageId, fileName, "data/images/" + fileName, 1024, NOW);
        if (annotation != null) {
            asset.applyAnnotation(annotation);
        }
        asset.transitionTo(io.github.dekkerding.engine.domain.model.image.ImageStatus.VECTORIZING);
        asset.transitionTo(io.github.dekkerding.engine.domain.model.image.ImageStatus.COMPLETED);
        imageAssetRepository.save(asset);
        ingestPhoto(imageId, fileName, vector);
    }

    private String uploadAwaitCompleted(String filename, String content) throws InterruptedException {
        Document document = ingestion.upload(filename, content.getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Document> found = documentRepository.findById(document.getId());
            if (found.isPresent() && found.get().getStatus().isTerminal()) {
                assertEquals(DocumentStatus.COMPLETED, found.get().getStatus(),
                        "预期成功终态，实际: " + found.get().getStatus() + " / " + found.get().getErrorMessage());
                return document.getId();
            }
            Thread.sleep(50);
        }
        fail("文档未在 15 秒内到达终态: " + document.getId());
        return null;
    }

    // ---------- 任务 4.1：跨模态路由 ----------

    @Test
    void 混合库下文本查询命中照片_分数降序_带模态标注() throws Exception {
        uploadAwaitCompleted("红塔游记.txt", "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。");
        // 查询"红塔"长度 2 → CLIP 替身编码为桶 2 单位向量；照片向量手工构造已知余弦
        ingestPhoto("img-1", "红塔.jpg", new float[]{0, 0, 1, 0});          // 余弦 1.0
        ingestPhoto("img-2", "红塔远眺.jpg", new float[]{0, 0, 0.6f, 0.8f}); // 余弦 0.6

        SearchResult result = search.search("红塔", 10, "image");

        assertEquals("image", result.getModality(), "响应必须携带模态标注");
        assertEquals(2, result.getItems().size(), "两张照片都应命中");
        assertTrue(result.getItems().get(0).getVectorScore() >= result.getItems().get(1).getVectorScore(),
                "命中必须按相似度降序");
        SearchHit top = result.getItems().get(0);
        assertEquals("红塔.jpg", top.getDocumentName(), "文件名随向量冗余，命中即展示名");
        assertEquals("img-1", top.getDocumentId(), "资源标识可反查来源");
        assertEquals("image", top.getSourceType(), "命中条目必须携带模态标注");
        assertEquals(SearchHit.Source.SEMANTIC, top.getSource(), "图片模态仅语义路（无全文）");
        assertEquals(1.0, top.getScore(), 1e-6, "单路直通：score 即余弦");
        assertNotNull(result.getTookMs());
    }

    @Test
    void 空间隔离_文本检索不命中图片_图片检索不命中文本() throws Exception {
        String docId = uploadAwaitCompleted("红塔游记.txt", "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。");
        ingestPhoto("img-1", "红塔.jpg", new float[]{0, 0, 1, 0});

        // 缺省（text）检索：候选集不含图片向量（spec：文本检索的候选集不含图片向量）
        SearchResult textResult = search.search("红塔", 10);
        assertEquals("text", textResult.getModality());
        assertTrue(textResult.getItems().size() > 0, "文本库非空，文本检索应有命中");
        for (SearchHit hit : textResult.getItems()) {
            assertEquals("text", hit.getSourceType(), "文本检索不得混入图片条目");
        }

        // image 检索：候选集不含文本向量（spec：图片检索的候选集不含文本向量）
        SearchResult imageResult = search.search("红塔", 10, "image");
        for (SearchHit hit : imageResult.getItems()) {
            assertEquals("image", hit.getSourceType());
            assertEquals("img-1", hit.getDocumentId(), "命中的必须是照片而非文档 " + docId);
        }
    }

    @Test
    void 纯文本库图片检索_空结果不报错() throws Exception {
        uploadAwaitCompleted("红塔游记.txt", "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。");

        SearchResult result = search.search("红塔", 10, "image");

        assertEquals("image", result.getModality());
        assertEquals(0, result.getItems().size(), "图片库为空是合法空结果（spec：不报错）");
    }

    @Test
    void 未知模态返回400() {
        EngineException e = assertThrows(EngineException.class,
                () -> search.search("红塔", 10, "audio"));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("模态"), "错误信息应说明支持的模态: " + e.getMessage());
    }

    // ---------- photo-semantic-search 任务 3.4：命中响应透出标注字段 ----------

    @Test
    void 图片命中透出标注字段_未标注图为null占位() throws Exception {
        ingestAnnotatedPhoto("img-anno", "小花.jpg", new float[]{0, 0, 1, 0},
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花", Arrays.asList("花", "红色"), true));
        // 未标注的存量照片：image_asset 行存在但标注字段为"未拆分"态
        ingestAnnotatedPhoto("img-legacy", "IMG_20240101_123456.jpg", new float[]{0, 0, 0.8f, 0.6f}, null);

        SearchResult result = search.search("红塔", 10, "image");
        assertEquals(2, result.getItems().size());

        SearchHit annotated = result.getItems().stream()
                .filter(h -> "img-anno".equals(h.getDocumentId())).findFirst().get();
        assertEquals("花", annotated.getSubject(), "命中必须透出主题（spec：标注字段透出）");
        assertEquals("红色的小花", annotated.getDescription());
        assertEquals(Arrays.asList("花", "红色"), annotated.getTags());
        assertEquals(Boolean.TRUE, annotated.getAnnotationMocked(), "mocked=true 显式透传");

        SearchHit legacy = result.getItems().stream()
                .filter(h -> "img-legacy".equals(h.getDocumentId())).findFirst().get();
        // 存量图占位语义：字段为空内容 + annotationMocked=null（未拆分 ≠ mock）
        // ——三态信号由 mocked 承担，前端据 mocked==null 渲染"未拆分"占位而非徽标
        assertEquals("", legacy.getSubject());
        assertEquals("", legacy.getDescription());
        assertTrue(legacy.getTags().isEmpty());
        assertNull(legacy.getAnnotationMocked(), "未拆分 = null 三态占位");
    }

    @Test
    void 图片阈值独立于文本阈值_低于CLIP阈值的命中被拦() throws Exception {
        ingestPhoto("img-strong", "相关照片.jpg", new float[]{0, 0, 1, 0});     // 余弦 1.0
        ingestPhoto("img-weak", "弱相关.jpg", new float[]{0, 0, 0.28f, 0.96f}); // 余弦 0.28

        // 默认 image 阈值 0.25：两条都过
        assertEquals(2, search.search("红塔", 10, "image").getItems().size());

        // 提高阈值到 0.5（design D8：阈值按模态独立配置）：0.28 被拦、1.0 保留
        SearchApplicationService strict = new SearchApplicationService(
                new EmbeddingProviderRegistry(Arrays.asList(new StubTextProvider(), clipProvider)),
                vectorStore, fullTextIndex, documentRepository,
                imageAssetRepository,
                Optional.empty(), Optional.empty(), Optional.of(reranker),
                10, 50, 60, 0.0, 0.5,
                0.40, true, 20,
                false, 1000, "5m");
        assertEquals(1, strict.search("红塔", 10, "image").getItems().size());
        assertEquals("相关照片.jpg", strict.search("红塔", 10, "image").getItems().get(0).getDocumentName());
    }

    @Test
    void CLIP降级_图片检索透出degraded与原因() throws Exception {
        ingestPhoto("img-1", "红塔.jpg", new float[]{0, 0, 1, 0});
        clipProvider.degraded = true; // 查询向量是哈希兜底

        SearchResult result = search.search("红塔", 10, "image");

        assertTrue(result.isDegraded(), "spec：降级标志必须透出（前端据此提示精度受限）");
        assertNotNull(result.getDegradedReason());
        assertTrue(result.getDegradedReason().contains("哈希兜底"),
                "降级原因应含引擎侧信息: " + result.getDegradedReason());
    }

    // ---------- photo-semantic-search 任务 4.1/4.2/4.3/4.5：双路召回 + 重排 ----------

    /**
     * 模拟一张"完整摄取"的照片：image_asset 行（含标注）+ 双向量一次入库
     * （(image, stub-clip) 像素路 + (image, stub-text-model) 描述路——沿生产管线
     * "两路合并单次 save"的纪律）。descVector 传 null = 未产生描述路向量。
     */
    private void ingestDualPathPhoto(String imageId, String fileName, float[] clipVector,
                                     io.github.dekkerding.engine.domain.model.image.ImageAnnotation annotation,
                                     float[] descVector) {
        io.github.dekkerding.engine.domain.model.image.ImageAsset asset =
                new io.github.dekkerding.engine.domain.model.image.ImageAsset(
                        imageId, fileName, "data/images/" + fileName, 1024, NOW);
        if (annotation != null) {
            asset.applyAnnotation(annotation);
        }
        asset.transitionTo(io.github.dekkerding.engine.domain.model.image.ImageStatus.VECTORIZING);
        asset.transitionTo(io.github.dekkerding.engine.domain.model.image.ImageStatus.COMPLETED);
        imageAssetRepository.save(asset);

        java.util.List<VectorEntry> entries = new ArrayList<>();
        entries.add(new VectorEntry(imageId, "image", 0, fileName, "stub-clip", false, clipVector, NOW));
        if (descVector != null) {
            entries.add(new VectorEntry(imageId, "image", 0, asset.annotationText(),
                    "stub-text-model", false, descVector, NOW));
        }
        vectorStore.save(imageId, entries);
    }

    /** e_i 单位向量速记 */
    private static float[] e(int bucket) {
        float[] v = new float[4];
        v[bucket] = 1f;
        return v;
    }

    @Test
    void 双路命中_RRF融合_双路命中者居首() throws Exception {
        // 查询"红塔"（2 字 → 两替身都编到桶 2）
        ingestDualPathPhoto("img-both", "双路.jpg", e(2),
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花", Arrays.asList("花"), true), e(2)); // 双路 cos 1.0
        ingestPhoto("img-pixel", "仅像素.jpg", new float[]{0, 0, 0.8f, 0.6f}); // 像素 cos 0.8，无描述向量

        // 重排显式关闭：本用例聚焦融合序（4.2 单独验收重排）
        SearchResult result = search.search("红塔", 10, "image", false, null);

        assertEquals(2, result.getItems().size());
        assertFalse(result.isReranked());
        assertEquals("重排已关闭", result.getRerankReason());

        SearchHit both = result.getItems().get(0);
        assertEquals("img-both", both.getDocumentId(), "双路命中的 RRF 分必高于单路冠军（1/61+1/61 > 1/62）");
        assertEquals(SearchHit.Source.BOTH, both.getSource(), "双路命中标注 BOTH");
        assertEquals(1.0, both.getVectorScore(), 1e-6, "像素路余弦");
        assertEquals(1.0, both.getTextScore(), 1e-6, "描述路余弦");
        assertTrue(both.getScore() > result.getItems().get(1).getScore(), "RRF 分降序");

        SearchHit pixelOnly = result.getItems().get(1);
        assertEquals(SearchHit.Source.SEMANTIC, pixelOnly.getSource());
        assertNull(pixelOnly.getTextScore(), "无描述路命中时描述分缺席");
    }

    @Test
    void 仅描述路命中_单路直通不报错() throws Exception {
        // 像素向量与查询正交（cos 0 < 0.25 阈值被拦）；描述向量对齐桶 2（cos 1.0）
        ingestDualPathPhoto("img-desc", "仅描述.jpg", e(0),
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花", Arrays.asList("花"), true), e(2));

        SearchResult result = search.search("红塔", 10, "image", false, null);

        assertEquals(1, result.getItems().size(), "描述路单路命中（spec：仅描述命中场景）");
        SearchHit hit = result.getItems().get(0);
        assertEquals("img-desc", hit.getDocumentId());
        assertNull(hit.getVectorScore(), "像素路未命中");
        assertEquals(1.0, hit.getScore(), 1e-6, "单路直通：score 即该路余弦");
        assertEquals(1.0, hit.getTextScore(), 1e-6);
    }

    @Test
    void 重排改变排序_携带重排分与reranked标志() throws Exception {
        // 召回序：img-near（像素 cos 1.0）> img-far（cos 0.6）；重排分经标注文本标记反转
        ingestDualPathPhoto("img-near", "近.jpg", e(2),
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "塔", "公园的红塔 #R0.1", Arrays.asList("塔"), true), e(0)); // 描述路不命中
        ingestDualPathPhoto("img-far", "远.jpg", new float[]{0, 0, 0.6f, 0.8f},
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花 #R0.9", Arrays.asList("花"), true), e(0));

        SearchResult result = search.search("红塔", 10, "image"); // 缺省 = 启用重排

        assertTrue(result.isReranked(), "spec：重排后响应携带 reranked=true");
        assertNull(result.getRerankReason());
        assertEquals(2, result.getItems().size());
        assertEquals("img-far", result.getItems().get(0).getDocumentId(),
                "重排分 0.9 > 0.1：召回第二应升到首位（spec：重排改变排序）");
        assertEquals("img-near", result.getItems().get(1).getDocumentId());
        assertEquals(0.9f, result.getItems().get(0).getRerankScore(), 1e-6, "命中携带重排分数");
        assertEquals(0.1f, result.getItems().get(1).getRerankScore(), 1e-6);
        assertEquals("红塔", reranker.capturedQuery, "重排器收到查询原文");
        assertEquals(2, reranker.capturedCandidates.size(), "有标注候选全部送重排");
    }

    @Test
    void 无标注候选殿后_标注候选优先() throws Exception {
        // 召回序：无标注图像素最强；有标注图重排分中等——重排后有标注者仍居首
        ingestPhoto("img-plain", "无标注.jpg", e(2));                       // cos 1.0，无标注
        ingestDualPathPhoto("img-anno", "有标注.jpg", new float[]{0, 0, 0.6f, 0.8f},
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花 #R0.5", Arrays.asList("花"), true), e(0));

        SearchResult result = search.search("红塔", 10, "image");

        assertTrue(result.isReranked());
        assertEquals("img-anno", result.getItems().get(0).getDocumentId(),
                "有标注候选参与重排，排在前（design D4：标注候选优先）");
        assertEquals("img-plain", result.getItems().get(1).getDocumentId(),
                "无标注候选不参与重排，按召回序殿后（spec：行为确定）");
        assertEquals(1, reranker.capturedCandidates.size(), "仅标注候选送重排");
        assertNull(result.getItems().get(1).getRerankScore(), "未参与重排的候选无重排分");
        assertNotNull(result.getItems().get(0).getRerankScore());
    }

    @Test
    void 重排器故障_直通不失败_原因可见() throws Exception {
        ingestDualPathPhoto("img-1", "照片.jpg", e(2),
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花", Arrays.asList("花"), true), e(0));
        reranker.fail = true; // 模拟 reranker 槽位不可用

        SearchResult result = search.search("红塔", 10, "image");

        assertFalse(result.isReranked(), "spec：重排器故障 → reranked=false");
        assertNotNull(result.getRerankReason());
        assertTrue(result.getRerankReason().contains("不可用"),
                "原因应说明重排器不可用: " + result.getRerankReason());
        assertEquals(1, result.getItems().size(), "检索本身不失败，召回结果直通返回");
        assertNull(result.getItems().get(0).getRerankScore());
    }

    @Test
    void 显式关闭重排_不触发重排调用() throws Exception {
        ingestDualPathPhoto("img-1", "照片.jpg", e(2),
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花", Arrays.asList("花"), true), e(0));

        SearchResult result = search.search("红塔", 10, "image", false, null);

        assertFalse(result.isReranked());
        assertEquals("重排已关闭", result.getRerankReason());
        assertEquals(0, reranker.invocations, "关闭时重排器零调用（spec：耗时不含重排）");
        assertEquals(1, result.getItems().size());
    }

    @Test
    void 候选全无标注_跳过重排_原因标注() throws Exception {
        ingestPhoto("img-legacy", "存量无标注.jpg", e(2));

        SearchResult result = search.search("红塔", 10, "image"); // 缺省启用重排

        assertFalse(result.isReranked());
        assertTrue(result.getRerankReason().contains("无标注"),
                "原因应说明候选无标注: " + result.getRerankReason());
        assertEquals(0, reranker.invocations);
        assertEquals(1, result.getItems().size(), "直通返回召回结果");
    }

    @Test
    void 三空间隔离_文本图片双向检索互不越界() throws Exception {
        // 混合三空间库：(text, bge) 文档块 + (image, clip) 像素 + (image, bge) 描述
        String docId = uploadAwaitCompleted("红塔游记.txt", "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。");
        ingestDualPathPhoto("img-anno", "小花.jpg", e(2),
                new io.github.dekkerding.engine.domain.model.image.ImageAnnotation(
                        "花", "红色的小花", Arrays.asList("花"), true), e(2));

        // 文本检索：候选集只含 (text, bge)——图片的描述向量虽同模型但空间不同，不得混入
        SearchResult textResult = search.search("红塔", 10, "text");
        assertTrue(textResult.getItems().size() > 0);
        for (SearchHit hit : textResult.getItems()) {
            assertEquals("text", hit.getSourceType());
            assertEquals(docId, hit.getDocumentId(), "文本检索不得命中图片（含描述路同模型空间）");
        }

        // 图片检索：候选集只含 (image, *) 两空间——文档块向量不得混入
        SearchResult imageResult = search.search("红塔", 10, "image", false, null);
        assertTrue(imageResult.getItems().size() > 0);
        SearchHit imageHit = imageResult.getItems().get(0);
        assertEquals("img-anno", imageHit.getDocumentId());
        assertEquals(SearchHit.Source.BOTH, imageHit.getSource(),
                "像素路 + 描述路（(image,bge) 空间）双路命中");
        assertNotNull(imageHit.getTextScore(), "描述路向量在图片模态内可命中（spec：双向检索互不越界）");
    }

    // ---------- 任务 4.2：缺省模态回归 ----------

    @Test
    void 缺省调用与显式text调用_结果逐字段一致() throws Exception {
        uploadAwaitCompleted("红塔游记.txt", "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。");
        ingestPhoto("img-1", "红塔.jpg", new float[]{0, 0, 1, 0});

        SearchResult implicit = search.search("红塔", 10);           // 既有客户端的调用方式
        SearchResult explicit = search.search("红塔", 10, "text");   // 显式 text

        assertEquals(implicit.getModality(), explicit.getModality());
        assertEquals(implicit.getItems().size(), explicit.getItems().size());
        for (int i = 0; i < implicit.getItems().size(); i++) {
            SearchHit a = implicit.getItems().get(i);
            SearchHit b = explicit.getItems().get(i);
            assertEquals(a.getDocumentId(), b.getDocumentId());
            assertEquals(a.getScore(), b.getScore(), 1e-9);
            assertEquals(a.getSourceType(), b.getSourceType());
            assertEquals(a.getSource(), b.getSource());
        }
    }
}
