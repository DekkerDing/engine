package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.DocumentDetail;
import io.github.dekkerding.engine.application.event.SearchCacheInvalidationEvent;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.model.resource.TextDocumentResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.EngineStatusQuery;
import io.github.dekkerding.engine.domain.repository.FullTextIndex;
import io.github.dekkerding.engine.domain.repository.VectorStore;
import io.github.dekkerding.engine.domain.service.TextChunker;
import io.github.dekkerding.engine.infrastructure.document.CompositeDocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 文档应用服务 —— 摄取管线与文档管理的用例编排者。
 *
 * <p>【用例一：上传受理（同步，毫秒级返回）】
 * <pre>
 * 校验格式/大小 → 落盘 data/documents/{id}.{ext} → 建 Document(PENDING) 记录
 *   → 提交固定线程池 → 立即返回文档 ID（前端拿着 ID 轮询状态）
 * </pre>
 *
 * <p>【用例二：异步摄取管线（工作线程）】design.md D4
 * <pre>
 * PARSING ──解析器取纯文本──▶ CHUNKING ──TextChunker 切块──▶ VECTORIZING
 *   ──批 16 编码（每批推进 vectorizedCount，轮询可见进度）──▶ 双入库（向量+全文）──▶ COMPLETED
 *   任何一步异常 ──▶ FAILED + 原因落库（已完成的分块不丢失）
 * </pre>
 *
 * <p>【用例三：文档管理】列表（按状态过滤）/ 详情（元数据+块+模型信息聚合）/ 删除
 * （SQLite 级联 + 内存向量索引 + Lucene 索引 + 上传文件四处联动清理）。
 *
 * <p>【教学注释 · 应用层不写"怎么做"】解析细节在 Parser、切块算法在 TextChunker、
 * 编码在 EmbeddingProvider、落库在仓储/索引端口——本类只决定"什么顺序、失败怎么办"。
 */
@Service
public class DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);

    /** 摄取批大小：与 ChannelEmbeddingProvider 的批一致（应用层分批是为了推进进度可见） */
    private static final int EMBED_BATCH_SIZE = 16;

    private final DocumentRepository documentRepository;
    private final CompositeDocumentParser parser;
    private final VectorStore vectorStore;
    private final FullTextIndex fullTextIndex;
    private final EmbeddingProvider textEmbeddingProvider;
    private final EngineStatusQuery engineStatus;
    private final TextChunker chunker;
    private final String uploadDir;
    private final long maxFileSizeBytes;
    /** 进程内事件总线：摄取数据变更 → 检索缓存失效（谁关心谁监听，本类不依赖检索服务） */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 固定 2 线程：摄取是重 IO+CPU（Python 推理）混合负载，并发超过通道处理能力
     * 只会堆积超时（JDK8 无虚拟线程；design.md D4 明确 2 线程够用且省内存）。
     */
    private final ExecutorService ingestExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ingest-worker");
        t.setDaemon(false); // 非 daemon：处理到一半的文档要跑完再退出（优雅停机）
        return t;
    });

    public DocumentApplicationService(DocumentRepository documentRepository,
                                      CompositeDocumentParser parser,
                                      VectorStore vectorStore,
                                      FullTextIndex fullTextIndex,
                                      List<EmbeddingProvider> providers,
                                      EngineStatusQuery engineStatus,
                                      @Value("${engine.documents.upload-dir:data/documents}") String uploadDir,
                                      @Value("${engine.documents.max-file-size-bytes:52428800}") long maxFileSizeBytes,
                                      @Value("${engine.documents.chunk.target-size:400}") int chunkTargetSize,
                                      @Value("${engine.documents.chunk.overlap-sentences:1}") int chunkOverlap,
                                      ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.parser = parser;
        this.vectorStore = vectorStore;
        this.fullTextIndex = fullTextIndex;
        // 注册制选 provider：按模态查表而非 if-else 模型名——后期 CLIP 注册 CROSS 模态即可共存
        this.textEmbeddingProvider = providers.stream()
                .filter(p -> p.modality() == EmbeddingModality.TEXT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "容器中缺少 TEXT 模态的 EmbeddingProvider（Python 通道未装配？）"));
        this.engineStatus = engineStatus;
        this.chunker = new TextChunker(chunkTargetSize, chunkOverlap);
        this.uploadDir = uploadDir;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.eventPublisher = eventPublisher;
    }

    // ---------- 用例一：上传受理（同步部分） ----------

    /**
     * 受理上传：校验 → 落盘 → 建档 → 提交异步处理。
     *
     * @throws EngineException 400：格式不支持 / 文件为空 / 超大小上限（不产生文档记录——
     *                         spec："不产生文档记录"，这三种是"用户传错了"，
     *                         与"损坏文件进 FAILED"（用户文件本身坏）是两类错误）
     */
    public Document upload(String filename, byte[] content) {
        if (filename == null || filename.trim().isEmpty()) {
            throw EngineException.badRequest("文件名不能为空");
        }
        if (!parser.supports(filename)) {
            throw EngineException.badRequest(
                    "不支持的文件类型: " + filename + "（支持: txt / pdf / docx）");
        }
        if (content == null || content.length == 0) {
            throw EngineException.badRequest("文件内容为空: " + filename);
        }
        if (content.length > maxFileSizeBytes) {
            throw EngineException.badRequest(String.format(
                    "文件超过大小上限: %d 字节 > %d 字节（%.1fMB）",
                    content.length, maxFileSizeBytes, maxFileSizeBytes / 1024.0 / 1024.0));
        }

        String id = UUID.randomUUID().toString();
        Path stored = Paths.get(uploadDir, id + extensionOf(filename));
        try {
            Files.createDirectories(stored.getParent());
            Files.write(stored, content);
        } catch (IOException e) {
            throw EngineException.internal("文件保存失败: " + filename, e);
        }

        Document document = new Document(id, filename, stored.toString(), content.length, Instant.now());
        documentRepository.save(document);
        log.info("文档受理: {} ({} 字节) → {}", filename, content.length, id);

        try {
            ingestExecutor.submit(() -> ingest(id));
        } catch (RejectedExecutionException e) {
            // 线程池已关闭（应用正在停机）：让文档显式失败而不是永远停在 PENDING
            document.markFailed("服务正在关闭，处理未受理");
            documentRepository.update(document);
            throw EngineException.internal("服务正在关闭，请稍后重试", e);
        }
        return document;
    }

    // ---------- 用例二：异步摄取管线 ----------

    /** 单文档摄取全流程（工作线程执行）。异常兜底：任何一步失败 → FAILED + 原因落库 */
    void ingest(String documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.error("摄取任务找不到文档记录: {}", documentId);
            return;
        }
        try {
            // ---- PARSING：提取纯文本 ----
            document.transitionTo(DocumentStatus.PARSING);
            documentRepository.update(document);
            String text = parser.parse(Files.readAllBytes(Paths.get(document.getStoredPath())),
                    document.getFilename());

            // ---- CHUNKING：切分语义块 ----
            document.transitionTo(DocumentStatus.CHUNKING);
            documentRepository.update(document);
            List<TextChunk> chunks = chunker.chunk(documentId, text);
            if (chunks.isEmpty()) {
                throw EngineException.badRequest("解析后无可索引文本（内容全为空白？）: " + document.getFilename());
            }
            documentRepository.saveChunks(documentId, chunks);
            document.recordChunks(chunks.size());
            documentRepository.update(document);

            // ---- VECTORIZING：批 16 编码，每批推进进度 ----
            document.transitionTo(DocumentStatus.VECTORIZING);
            documentRepository.update(document);
            List<VectorEntry> entries = new ArrayList<>(chunks.size());
            for (int from = 0; from < chunks.size(); from += EMBED_BATCH_SIZE) {
                List<TextChunk> batch = chunks.subList(from, Math.min(from + EMBED_BATCH_SIZE, chunks.size()));
                List<TextDocumentResource> resources = new ArrayList<>(batch.size());
                for (TextChunk chunk : batch) {
                    resources.add(new TextDocumentResource(documentId, chunk.getChunkIndex(), chunk.getText()));
                }
                List<Embedding> embeddings = textEmbeddingProvider.embedBatch(resources);
                if (embeddings.size() != batch.size()) {
                    throw EngineException.downstream("引擎返回向量数与输入不符: "
                            + embeddings.size() + " != " + batch.size(), null);
                }
                for (int i = 0; i < batch.size(); i++) {
                    TextChunk chunk = batch.get(i);
                    Embedding embedding = embeddings.get(i);
                    entries.add(new VectorEntry(documentId, "text", chunk.getChunkIndex(),
                            chunk.getText(), embedding.getModelKey(), embedding.isDegraded(),
                            embedding.getVector(), Instant.now()));
                    if (embedding.isDegraded()) {
                        document.markDegraded(); // spec：降级模式必须显式标记（不悄悄给假向量）
                    }
                }
                document.recordVectorized(entries.size());
                documentRepository.update(document);
            }

            // ---- 双入库：向量（SQLite+内存索引）与全文（Lucene） ----
            vectorStore.save(documentId, entries);
            fullTextIndex.indexDocument(documentId, chunks);

            // ---- COMPLETED：成功终态 ----
            document.transitionTo(DocumentStatus.COMPLETED);
            documentRepository.update(document);
            // 新数据可检索：旧查询的缓存结果已过期（不含新文档），通知失效
            eventPublisher.publishEvent(new SearchCacheInvalidationEvent(
                    documentId, SearchCacheInvalidationEvent.ChangeType.INGESTED));
            log.info("文档摄取完成: {} ({} 块, degraded={})",
                    document.getFilename(), chunks.size(), document.isDegraded());
        } catch (Exception e) {
            // 失败兜底：原因是用户可见的（详情页/列表展示），不是只进日志的内部信息
            String reason = e instanceof EngineException
                    ? e.getMessage() : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            document.markFailed("摄取失败: " + reason);
            documentRepository.update(document);
            log.error("文档摄取失败: {} ({})", document.getFilename(), documentId, e);
        }
    }

    // ---------- 用例三：文档管理 ----------

    /** 文档列表（上传时间倒序）。status 为 null 时返回全部 */
    public List<Document> listDocuments(DocumentStatus status) {
        List<Document> all = documentRepository.findAll();
        if (status == null) {
            return all;
        }
        List<Document> filtered = new ArrayList<>();
        for (Document document : all) {
            if (document.getStatus() == status) {
                filtered.add(document);
            }
        }
        return filtered;
    }

    /** 文档详情聚合：元数据 + 分块列表 + 模型/维度/降级原因 */
    public DocumentDetail getDocumentDetail(String id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> EngineException.notFound("文档不存在: " + id));
        List<TextChunk> chunks = documentRepository.findChunks(id);

        String degradedReason = null;
        if (document.isDegraded()) {
            degradedReason = engineStatus.status().isDegraded()
                    ? "引擎降级运行: " + engineStatus.status().getLastError()
                    : "摄取时引擎处于降级模式（哈希兜底向量），当前引擎已恢复";
        }
        return new DocumentDetail(document, chunks,
                textEmbeddingProvider.modelKey(), textEmbeddingProvider.dimension(), degradedReason);
    }

    /** 删除文档：四处联动清理（SQLite 级联 + 内存向量索引 + Lucene + 上传文件） */
    public void deleteDocument(String id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> EngineException.notFound("文档不存在: " + id));
        documentRepository.deleteById(id);       // documents + chunks + vector_entries（级联事务）
        vectorStore.deleteByDocument(id);        // 表行已级联删，这里清内存索引（幂等双删无害）
        fullTextIndex.deleteDocument(id);        // Lucene 块索引
        try {
            Files.deleteIfExists(Paths.get(document.getStoredPath()));
        } catch (IOException e) {
            log.warn("上传文件删除失败（不影响数据一致性）: {}", document.getStoredPath(), e);
        }
        // spec 场景"删除后不再命中"：缓存里的旧命中必须作废，否则 5 分钟内搜索仍返回已删内容
        eventPublisher.publishEvent(new SearchCacheInvalidationEvent(
                id, SearchCacheInvalidationEvent.ChangeType.DELETED));
        log.info("文档删除完成: {} ({})", document.getFilename(), id);
    }

    /** 优雅停机：等在途摄取跑完（daemon=false 的 worker 会阻塞 JVM 退出至任务完成） */
    @PreDestroy
    public void shutdown() {
        ingestExecutor.shutdown();
    }

    private String extensionOf(String filename) {
        return filename.substring(filename.lastIndexOf('.')).toLowerCase();
    }
}
