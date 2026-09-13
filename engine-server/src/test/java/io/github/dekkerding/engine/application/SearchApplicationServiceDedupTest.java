package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.SearchHit;
import io.github.dekkerding.engine.application.dto.SearchResult;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.model.engine.EngineStatus;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.EngineStatusQuery;
import io.github.dekkerding.engine.infrastructure.document.CompositeDocumentParser;
import io.github.dekkerding.engine.infrastructure.document.TxtDocumentParser;
import io.github.dekkerding.engine.infrastructure.fulltext.LuceneFullTextIndex;
import io.github.dekkerding.engine.infrastructure.persistence.DatabaseMigrator;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteDocumentRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 检索结果去重测试 —— 7.1 手测发现的缺陷回归（spec vector-search「混合融合排序」：
 * "文档内或跨文档的重复块 SHALL 去重"）。
 *
 * <p>【缺陷复现】同一文件上传两次（两个文档 ID、内容相同的块），
 * 修复前融合结果会把相同片段列出两条——用户看到的是复读清单。
 *
 * <p>【测试策略】与 DocumentApplicationServiceTest 同款：假 provider +
 * 真实 SQLite/Lucene，走完整摄取→检索链路（去重发生在融合后、截断前，
 * 只有用真实两路命中才能测到它的位置正确性）。
 */
class SearchApplicationServiceDedupTest {

    @TempDir
    Path tempDir;

    private static class StubEmbeddingProvider implements EmbeddingProvider {
        @Override
        public EmbeddingModality modality() {
            return EmbeddingModality.TEXT;
        }

        @Override
        public String modelKey() {
            return "stub-text-model";
        }

        @Override
        public int dimension() {
            return 4;
        }

        @Override
        public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            List<Embedding> result = new ArrayList<>();
            for (VectorableResource resource : resources) {
                float[] vector = new float[4];
                vector[resource.asText().length() % 4] = 1f;
                result.add(new Embedding(vector, "stub-text-model", false));
            }
            return result;
        }
    }

    private SqliteConnectionManager connectionManager;
    private SqliteVectorStore vectorStore;
    private LuceneFullTextIndex fullTextIndex;
    private DocumentRepository documentRepository;
    private DocumentApplicationService ingestion;
    private SearchApplicationService search;

    @BeforeEach
    void 装配() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate();
        documentRepository = new SqliteDocumentRepository(connectionManager);
        vectorStore = new SqliteVectorStore(connectionManager);
        vectorStore.preload();
        fullTextIndex = new LuceneFullTextIndex(tempDir.resolve("lucene").toString());

        StubEmbeddingProvider provider = new StubEmbeddingProvider();
        EngineStatusQuery engineStatus = () -> new EngineStatus(
                true, false, "stub-channel",
                Arrays.asList("stub-text-model"), 4, null);

        ingestion = new DocumentApplicationService(
                documentRepository,
                new CompositeDocumentParser(Arrays.asList(new TxtDocumentParser())),
                vectorStore,
                fullTextIndex,
                Arrays.asList(provider),
                engineStatus,
                tempDir.resolve("documents").toString(),
                50 * 1024 * 1024,
                400, 1,
                event -> { });

        // 缓存关掉：去重断言要每次都走真实融合，不受缓存副本影响
        search = new SearchApplicationService(
                new EmbeddingProviderRegistry(Arrays.asList(provider)),
                vectorStore,
                fullTextIndex,
                documentRepository,
                new io.github.dekkerding.engine.infrastructure.persistence.SqliteImageAssetRepository(connectionManager),
                Optional.of(engineStatus),
                Optional.empty(), // 无 CLIP：文本模态用不到 vision 状态
                Optional.empty(), // 无重排器：文本模态不消费
                10, 50, 60, 0.0, 0.25,
                0.40, true, 20,
                false, 1000, "5m");
    }

    @AfterEach
    void 收尾() {
        ingestion.shutdown();
        fullTextIndex.shutdown();
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

    /** 同一内容上传两份（不同文件名/文档 ID）→ 检索结果中相同片段只出现一次 */
    @Test
    void 跨文档重复块只保留一条() throws Exception {
        String content = "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。";
        uploadAwaitCompleted("红塔复制甲.txt", content);
        uploadAwaitCompleted("红塔复制乙.txt", content);

        SearchResult result = search.search("红塔", 10);

        assertTrue(result.getItems().size() >= 1, "至少应有一条命中");
        Set<String> seenSnippets = new HashSet<>();
        for (SearchHit hit : result.getItems()) {
            assertTrue(seenSnippets.add(hit.getSnippet().trim()),
                    "跨文档重复片段应被去重，重复内容: " + hit.getSnippet());
        }
    }

    /** 去重发生在截断前：topK=1 时返回的应是最优的那条，而非被重复块挤掉 */
    @Test
    void 去重后topK截断仍返回最优命中() throws Exception {
        String content = "公园中央的红塔下开着小花，夕阳把塔身染成暖红色。";
        uploadAwaitCompleted("红塔复制甲.txt", content);
        uploadAwaitCompleted("红塔复制乙.txt", content);

        SearchResult result = search.search("红塔", 1);

        assertEquals(1, result.getItems().size());
        assertTrue(result.getItems().get(0).getSnippet().contains("红塔"));
    }
}
