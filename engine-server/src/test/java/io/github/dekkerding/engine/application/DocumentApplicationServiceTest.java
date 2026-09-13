package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.DocumentDetail;
import io.github.dekkerding.engine.domain.exception.EngineException;
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
import io.github.dekkerding.engine.infrastructure.document.DocumentParser;
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
 * 摄取管线应用层测试 —— 任务 3.1/3.2/3.3 的验收：
 * 上传受理→异步→COMPLETED、失败路径（解析/引擎超时）、降级标记、文档管理联动清理。
 *
 * <p>【测试策略 · 端口替身（Test Double）】
 * EmbeddingProvider 用假实现（可控：正常/抛异常/降级），其余全部用真实件
 * （SQLite/Lucene/解析器）——被测的是"编排逻辑"，只把昂贵的 Python 子进程换成替身。
 * 这正是依赖倒置的红利：应用层不关心 provider 背后是 Python 还是测试桩。
 */
class DocumentApplicationServiceTest {

    @TempDir
    Path tempDir;

    // ---------- 测试替身 ----------

    /** 可控的向量化替身：fail=模拟引擎超时；degraded=模拟哈希兜底 */
    private static class StubEmbeddingProvider implements EmbeddingProvider {
        boolean fail;
        boolean degraded;

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
            if (fail) {
                throw EngineException.downstream("Python 引擎不可达（模拟调用超时）", null);
            }
            List<Embedding> result = new ArrayList<>();
            for (VectorableResource resource : resources) {
                // 确定性向量：按文本长度分桶，同长度文本同向量（保证"同文档独有内容"可被检索）
                float[] vector = new float[4];
                vector[resource.asText().length() % 4] = 1f;
                result.add(new Embedding(vector, "stub-text-model", degraded));
            }
            return result;
        }
    }

    /** 总是解析失败的假 PDF 解析器（模拟损坏文件） */
    private static class BrokenPdfParser implements DocumentParser {
        @Override
        public String supportedExtension() {
            return "pdf";
        }

        @Override
        public String parse(byte[] content, String filename) {
            throw EngineException.internal("PDF 结构损坏，无法提取文本: " + filename, null);
        }
    }

    // ---------- 真实组件 + 被测服务 ----------

    private SqliteConnectionManager connectionManager;
    private SqliteVectorStore vectorStore;
    private LuceneFullTextIndex fullTextIndex;
    private DocumentRepository documentRepository;
    private StubEmbeddingProvider provider;
    private DocumentApplicationService service;

    @BeforeEach
    void 装配() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate();
        documentRepository = new SqliteDocumentRepository(connectionManager);
        vectorStore = new SqliteVectorStore(connectionManager);
        vectorStore.preload();
        fullTextIndex = new LuceneFullTextIndex(tempDir.resolve("lucene").toString());

        provider = new StubEmbeddingProvider();
        EngineStatusQuery engineStatus = () -> new EngineStatus(
                true, provider.degraded, "stub-channel",
                Arrays.asList("stub-text-model"), 4,
                provider.degraded ? "真实模型加载失败，哈希兜底" : null);

        service = new DocumentApplicationService(
                documentRepository,
                new CompositeDocumentParser(Arrays.asList(new TxtDocumentParser(), new BrokenPdfParser())),
                vectorStore,
                fullTextIndex,
                Arrays.asList(provider),
                Optional.of(engineStatus),
                tempDir.resolve("documents").toString(),
                50 * 1024 * 1024,
                400, 1,
                event -> { /* 哑事件总线：单测环境无 Spring 容器，缓存失效无缓存可清 */ });
    }

    @AfterEach
    void 收尾() {
        service.shutdown();
        fullTextIndex.shutdown();
    }

    /** 轮询等待文档到达终态（异步管线在 2 线程池里跑，测试必须等它完成） */
    private Document awaitTerminal(String documentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Document> found = documentRepository.findById(documentId);
            if (found.isPresent() && found.get().getStatus().isTerminal()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        fail("文档未在 15 秒内到达终态: " + documentId);
        return null;
    }

    private String uploadAwaitCompleted(String filename, String content) throws InterruptedException {
        Document document = service.upload(filename, content.getBytes(StandardCharsets.UTF_8));
        Document done = awaitTerminal(document.getId());
        assertEquals(DocumentStatus.COMPLETED, done.getStatus(),
                "预期成功终态，实际: " + done.getStatus() + " / " + done.getErrorMessage());
        return document.getId();
    }

    // ---------- 任务 3.1：摄取主链路 ----------

    @Test
    void 上传txt_最终到达COMPLETED且块数大于0() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            content.append("公园中央的红塔下开着小花，塔影落在花坛边上。");
        }

        Document uploaded = service.upload("红塔漫步.txt", content.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(DocumentStatus.PENDING, uploaded.getStatus());

        Document done = awaitTerminal(uploaded.getId());

        assertEquals(DocumentStatus.COMPLETED, done.getStatus());
        assertTrue(done.getChunkCount() > 0, "长文应切出多块");
        assertEquals(done.getChunkCount(), done.getVectorizedCount(), "全部块都应完成向量化");
        assertEquals(done.getChunkCount(), vectorStore.count(), "向量应全部入库");
        assertFalse(done.isDegraded());
    }

    @Test
    void 摄取完成后全文检索可命中() throws Exception {
        uploadAwaitCompleted("红塔.txt", "公园中央矗立着一座红塔，夕阳下塔身通红。");

        assertTrue(fullTextIndex.search("红塔", 10).size() > 0, "摄取完成后 Lucene 应可命中");
    }

    @Test
    void 摄取完成后向量检索可命中() throws Exception {
        uploadAwaitCompleted("红塔.txt", "公园中央矗立着一座红塔。");

        // 替身向量按"文本长度 % 4"分桶：用相同长度的查询文本撞同一桶
        float[] probe = new float[4];
        probe["公园中央矗立着一座红塔。".length() % 4] = 1f;
        assertTrue(vectorStore.search(probe, 10, null, null).size() > 0, "摄取完成后向量库应可命中");
    }

    // ---------- 任务 3.2：失败路径与降级 ----------

    @Test
    void 超限文件被拒绝_不产生文档记录() {
        // 单独构造一个小上限（10 字节）的服务实例：超限校验在上传受理的同步路径
        DocumentApplicationService strict = new DocumentApplicationService(
                documentRepository,
                new CompositeDocumentParser(Arrays.asList(new TxtDocumentParser())),
                vectorStore, fullTextIndex, Arrays.asList(provider),
                Optional.<EngineStatusQuery>of(() -> EngineStatus.down("stub", null)),
                tempDir.resolve("documents").toString(),
                10, 400, 1,
                event -> { });
        try {
            EngineException e = assertThrows(EngineException.class,
                    () -> strict.upload("超大.txt", "这一段内容超过十个字节。".getBytes()));
            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("上限"), "错误信息应说明大小上限: " + e.getMessage());
            assertTrue(documentRepository.findAll().isEmpty(), "被拒上传不得产生文档记录");
        } finally {
            strict.shutdown();
        }
    }

    @Test
    void 空内容与不支持格式被拒绝() {
        assertEquals(400, assertThrows(EngineException.class,
                () -> service.upload("空.txt", new byte[0])).getCode());
        assertEquals(400, assertThrows(EngineException.class,
                () -> service.upload("病毒.exe", "MZ...".getBytes())).getCode());
        assertTrue(documentRepository.findAll().isEmpty(), "被拒上传不得产生文档记录");
    }

    @Test
    void 损坏文件进入FAILED且原因可见() throws Exception {
        Document uploaded = service.upload("损坏.pdf", "这不是真的pdf内容".getBytes());

        Document done = awaitTerminal(uploaded.getId());

        assertEquals(DocumentStatus.FAILED, done.getStatus());
        assertNotNull(done.getErrorMessage());
        assertTrue(done.getErrorMessage().contains("PDF"), "失败原因应含具体解析错误: " + done.getErrorMessage());
    }

    @Test
    void 引擎不可达进入FAILED_分块结果不丢失() throws Exception {
        provider.fail = true; // 从一开始就模拟 Python 引擎超时

        Document uploaded = service.upload("正常.txt", "公园中央矗立着一座红塔，塔下开满小花。".getBytes());

        Document done = awaitTerminal(uploaded.getId());

        assertEquals(DocumentStatus.FAILED, done.getStatus());
        assertTrue(done.getErrorMessage().contains("不可达"), "失败原因应含下游信息: " + done.getErrorMessage());
        // spec：已完成的分块结果不丢失（FAILED 前已完成 CHUNKING）
        assertTrue(documentRepository.findChunks(uploaded.getId()).size() > 0);
    }

    @Test
    void 降级模式_文档与向量都带降级标记() throws Exception {
        provider.degraded = true; // 模拟真实模型加载失败、哈希兜底

        String id = uploadAwaitCompleted("降级.txt", "公园中央矗立着一座红塔。");

        Document document = documentRepository.findById(id).get();
        assertTrue(document.isDegraded(), "spec 要求文档级降级标记");

        // 块级标记随向量条目透传（检索结果要如实提示精度受限）
        float[] probe = new float[4];
        probe["公园中央矗立着一座红塔。".length() % 4] = 1f;
        assertTrue(vectorStore.search(probe, 10, null, null).get(0).getEntry().isDegraded());

        // 详情聚合出降级原因
        DocumentDetail detail = service.getDocumentDetail(id);
        assertNotNull(detail.getDegradedReason());
        assertTrue(detail.getDegradedReason().contains("哈希兜底"));
    }

    // ---------- 任务 3.3：文档管理 ----------

    @Test
    void 删除文档_四处联动清理() throws Exception {
        String keepId = uploadAwaitCompleted("保留.txt", "维也纳金色大厅的演出座无虚席。");
        String dropId = uploadAwaitCompleted("删除.txt", "公园中央矗立着一座红塔，塔下开满小花。");
        long countBefore = vectorStore.count();

        service.deleteDocument(dropId);

        // 1) 文档记录与向量：库计数减去被删文档的块数
        assertTrue(vectorStore.count() < countBefore);
        assertEquals(404, assertThrows(EngineException.class,
                () -> service.getDocumentDetail(dropId)).getCode());
        // 2) 全文索引：该文档独有内容不再命中
        assertTrue(fullTextIndex.search("红塔", 10).isEmpty(), "被删文档的块必须从全文索引消失");
        // 3) 保留文档不受影响
        assertFalse(fullTextIndex.search("金色大厅", 10).isEmpty());
        assertNotNull(service.getDocumentDetail(keepId));
        // 4) 上传文件已删（目录里只剩保留文档的文件）
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(tempDir.resolve("documents"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void 删除不存在的文档返回404() {
        assertEquals(404, assertThrows(EngineException.class,
                () -> service.deleteDocument("no-such-id")).getCode());
    }

    @Test
    void 列表按状态过滤() throws Exception {
        uploadAwaitCompleted("完成.txt", "公园中央矗立着一座红塔。");
        provider.fail = true;
        Document failed = service.upload("失败.txt", "这一次会失败。".getBytes());
        awaitTerminal(failed.getId());

        assertEquals(2, service.listDocuments(null).size());
        assertEquals(1, service.listDocuments(DocumentStatus.COMPLETED).size());
        assertEquals(1, service.listDocuments(DocumentStatus.FAILED).size());
        assertEquals("失败.txt", service.listDocuments(DocumentStatus.FAILED).get(0).getFilename());
    }

    @Test
    void 详情聚合块列表与模型信息() throws Exception {
        String id = uploadAwaitCompleted("详情.txt", "公园中央矗立着一座红塔，塔下开满小花。");

        DocumentDetail detail = service.getDocumentDetail(id);

        assertTrue(detail.getChunks().size() > 0);
        assertEquals("stub-text-model", detail.getModelKey());
        assertEquals(4, detail.getDimension());
        assertNull(detail.getDegradedReason());
    }
}
