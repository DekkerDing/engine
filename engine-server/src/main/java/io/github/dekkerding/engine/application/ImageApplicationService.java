package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.ImageDetail;
import io.github.dekkerding.engine.application.event.SearchCacheInvalidationEvent;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.image.ImageAnnotation;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.image.ImageStatus;
import io.github.dekkerding.engine.domain.model.resource.ImageAssetResource;
import io.github.dekkerding.engine.domain.model.resource.TextDocumentResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.repository.AnnotationProvider;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.ImageAssetRepository;
import io.github.dekkerding.engine.domain.repository.VectorStore;
import io.github.dekkerding.engine.domain.repository.VisionStatusQuery;
import io.github.dekkerding.engine.domain.service.ImageFormatGuard;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 图片应用服务 —— 图片上传与异步向量化的用例编排者（对照 DocumentApplicationService）。
 *
 * <p>【用例一：上传受理（同步，毫秒级返回）】
 * <pre>
 * 校验（白名单+魔数+大小上限）→ 落盘 data/images/{id}.{ext} → 建 ImageAsset(PENDING)
 *   → 提交线程池 → 立即返回图片 ID（前端轮询状态）
 * </pre>
 *
 * <p>【用例二：异步向量化（工作线程）】图片管线是文档管线的"压缩版"——
 * 没有 PARSING/CHUNKING（一图一向量，spec），直接：
 * <pre>
 * VECTORIZING ──整图经 CLIP 图像塔编码──▶ 恰一条向量入库(source_type='image')──▶ COMPLETED
 *   任何异常 ──▶ FAILED + 原因落库（不阻塞其他图片）
 * </pre>
 *
 * <p>【教学注释 · 与文档摄取为什么是两个服务】状态机不同（轻量版）、校验规则不同
 * （魔数 vs 解析器探测）、入库路径不同（无全文索引）——硬塞进一个服务就是
 * 一个类两套 if。用例边界沿"用户动词"切（传文档/传图片），自然且稳定。
 */
@Service
public class ImageApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ImageApplicationService.class);

    private final ImageAssetRepository imageAssetRepository;
    private final VectorStore vectorStore;
    private final EmbeddingProviderRegistry providerRegistry;
    private final AnnotationProvider annotationProvider;
    private final VisionStatusQuery visionStatus;
    private final ImageFormatGuard formatGuard;
    /** 进程内事件总线：图片入库 → 检索缓存失效（与文档摄取同一机制） */
    private final ApplicationEventPublisher eventPublisher;
    private final String uploadDir;
    private final long maxFileSizeBytes;

    /**
     * 独立单线程：图片向量化是单次 CLIP 调用（秒级），独立池避免与文档摄取
     * 互相排队——传一张照片不该等在大 PDF 后面。
     */
    private final ExecutorService vectorizeExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "image-vectorize-worker");
        t.setDaemon(false); // 非 daemon：处理到一半的图片要跑完再退出（优雅停机）
        return t;
    });

    public ImageApplicationService(ImageAssetRepository imageAssetRepository,
                                   VectorStore vectorStore,
                                   EmbeddingProviderRegistry providerRegistry,
                                   AnnotationProvider annotationProvider,
                                   VisionStatusQuery visionStatus,
                                   @Value("${engine.images.upload-dir:data/images}") String uploadDir,
                                   @Value("${engine.images.max-file-size-bytes:20971520}") long maxFileSizeBytes,
                                   ApplicationEventPublisher eventPublisher) {
        this.imageAssetRepository = imageAssetRepository;
        this.vectorStore = vectorStore;
        this.providerRegistry = providerRegistry;
        this.annotationProvider = annotationProvider;
        this.visionStatus = visionStatus;
        this.formatGuard = new ImageFormatGuard();
        this.uploadDir = uploadDir;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.eventPublisher = eventPublisher;
    }

    // ---------- 用例一：上传受理（同步部分） ----------

    /**
     * 受理上传（既有签名 = 无覆盖描述，行为不变）。
     */
    public ImageAsset upload(String filename, byte[] content) {
        return upload(filename, content, null);
    }

    /**
     * 受理上传：校验 → 落盘 → 建档 → 提交异步管线（标注 + 双向量化）。
     *
     * @param caption 可选的覆盖描述（上传入参）：mock 标注器直接采纳该文本构造标注，
     *                使语义链路可被调用方控制验证；真实 VLM 上线后语义保持为"人工修正覆盖"
     * @throws EngineException 400：格式不支持 / 魔数不符 / 文件为空 / 超大小上限
     *                         （三类"用户传错了"都不产生图片记录、不落盘——spec）
     */
    public ImageAsset upload(String filename, byte[] content, String caption) {
        ImageAsset imageAsset = ingest(filename, content, caption);
        try {
            vectorizeExecutor.submit(() -> vectorize(imageAsset.getId(), caption));
        } catch (RejectedExecutionException e) {
            // 线程池已关闭（应用正在停机）：让图片显式失败而不是永远停在 PENDING
            imageAsset.markFailed("服务正在关闭，处理未受理");
            imageAssetRepository.update(imageAsset);
            throw EngineException.internal("服务正在关闭，请稍后重试", e);
        }
        return imageAsset;
    }

    /**
     * 受理的同步部分：校验 → 落盘 → 建档（不提交管线）。
     * 批量导入（photo-semantic-search）复用：预校验异常在此原样抛出，
     * 合格文件由导入任务在自己的并发池上执行 {@link #vectorize(String, String)}
     * （包级可见——导入服务同包复用管线，不复制逻辑）。
     */
    ImageAsset ingest(String filename, byte[] content, String caption) {
        String extension = formatGuard.validateAndNormalize(filename, content);
        if (content.length > maxFileSizeBytes) {
            throw EngineException.badRequest(String.format(
                    "图片超过大小上限: %d 字节 > %d 字节（%.1fMB）",
                    content.length, maxFileSizeBytes, maxFileSizeBytes / 1024.0 / 1024.0));
        }

        String id = UUID.randomUUID().toString();
        Path stored = Paths.get(uploadDir, id + "." + extension);
        try {
            Files.createDirectories(stored.getParent());
            Files.write(stored, content);
        } catch (IOException e) {
            throw EngineException.internal("图片保存失败: " + filename, e);
        }

        // 【跨进程路径铁律】storedPath 会跨进程边界传给 Python（CLIP 按路径读像素），
        // 必须绝对化——Java 与 Python 子进程的 CWD 不同（launcher 把 Python 定在脚本目录），
        // 相对路径两边解析出不同文件。文档管线传的是文本内容，无此约束
        ImageAsset imageAsset = new ImageAsset(id, filename,
                stored.toAbsolutePath().toString(), content.length, Instant.now());
        imageAssetRepository.save(imageAsset);
        log.info("图片受理: {} ({} 字节) → {}", filename, content.length, id);
        return imageAsset;
    }

    // ---------- 用例二：异步摄取管线（标注 + 双向量化） ----------

    /** 既有签名（无 caption），行为 = 管线内 mock 标注走文件名派生 */
    void vectorize(String imageId) {
        vectorize(imageId, null);
    }

    /**
     * 单图片摄取全流程（工作线程执行）：
     * <pre>
     * VECTORIZING ──①语义标注（mock 先行；失败不阻塞，原因落 annotationError）
     *            ──②像素路：整图经 CLIP 图像塔 → (image, clip) 空间一向量
     *            ──③描述路：标注文本经 bge → (image, text) 空间一向量（有标注才走）
     *            ──▶ COMPLETED（①③的失败都不阻塞完成；只有②失败 → FAILED）
     * </pre>
     * 双向量空间隔离由 VectorStore 的 (sourceType, modelKey) 闸门承担（design D2）。
     */
    void vectorize(String imageId, String caption) {
        ImageAsset imageAsset = imageAssetRepository.findById(imageId).orElse(null);
        if (imageAsset == null) {
            log.error("摄取任务找不到图片记录: {}", imageId);
            return;
        }
        try {
            imageAsset.transitionTo(ImageStatus.VECTORIZING);
            imageAssetRepository.update(imageAsset);

            // ① 语义标注（design D5：解析后、向量化前）。失败 = 图片仍有像素路可用，
            //    原因落 annotationError 供详情页追溯——spec：标注失败不阻塞入库
            try {
                // filename=原始文件名（mock 标注按它派生语义——存储路径是 uuid 命名，
                // 拿它派生只会得到无语义片段，5.3 缩样实测暴露后修正）
                ImageAnnotation annotation = annotationProvider.annotate(
                        imageAsset.getStoredPath(), imageAsset.getFilename(), caption);
                imageAsset.applyAnnotation(annotation);
                log.info("图片标注完成: {} (mocked={}, subject=\"{}\")",
                        imageAsset.getFilename(), annotation.isMocked(), annotation.getSubject());
            } catch (Exception e) {
                imageAsset.markAnnotationFailed("标注失败: " + reasonOf(e));
                log.warn("图片标注失败（不阻塞入库）: {} ({})", imageAsset.getFilename(), reasonOf(e));
            }

            // ② 像素路：整图一资源（一图一向量）。文件名随向量冗余入库，命中列表不用回查
            ImageAssetResource resource = new ImageAssetResource(
                    imageId, imageAsset.getStoredPath(), imageAsset.getFilename());
            EmbeddingProvider clipProvider = providerRegistry.require(EmbeddingModality.CROSS);
            List<Embedding> embeddings = clipProvider.embedBatch(Collections.singletonList(resource));
            if (embeddings.size() != 1) {
                throw EngineException.downstream("CLIP 返回向量数与输入不符（期望 1 实得 "
                        + embeddings.size() + "）", null);
            }

            Embedding embedding = embeddings.get(0);
            if (embedding.isDegraded()) {
                imageAsset.markDegraded(); // spec：降级模式必须显式标记（不悄悄给假向量）
            }
            List<VectorEntry> entries = new ArrayList<>();
            entries.add(new VectorEntry(imageId, "image", 0, imageAsset.getFilename(),
                    embedding.getModelKey(), embedding.isDegraded(),
                    embedding.getVector(), Instant.now()));

            // ③ 描述路：标注文本经文本引擎编码 → 图片的第二向量。
            //    VectorEntry.text 冗余存标注文本——它同时是重排阶段的取文本来源（design D2）。
            //    失败不阻塞：本路向量缺席、像素路照常；标注字段保留（spec：任一路失败保持
            //    另一路可用），原因记 annotationError 追溯
            if (imageAsset.isAnnotated()) {
                try {
                    entries.add(embedDescription(imageAsset));
                } catch (Exception e) {
                    imageAsset.markDescriptionVectorizationFailed("描述路向量化失败: " + reasonOf(e));
                    log.warn("图片描述路向量化失败（像素路不受影响）: {} ({})",
                            imageAsset.getFilename(), reasonOf(e));
                }
            }

            // ④ 一次入库：VectorStore.save 是"先清后写"幂等语义（重传不留旧向量）——
            //    双向量必须合并成单次调用，分两次调第二次会清掉第一路
            vectorStore.save(imageId, entries);

            imageAsset.transitionTo(ImageStatus.COMPLETED);
            imageAssetRepository.update(imageAsset);
            // 新数据可检索：旧查询的缓存结果已过期，通知失效（与文档摄取同一事件）
            eventPublisher.publishEvent(new SearchCacheInvalidationEvent(
                    imageId, SearchCacheInvalidationEvent.ChangeType.INGESTED));
            log.info("图片摄取完成: {} (degraded={}, annotated={})",
                    imageAsset.getFilename(), imageAsset.isDegraded(), imageAsset.isAnnotated());
        } catch (Exception e) {
            // 失败兜底（只有②像素路或状态流转会走到这里）：原因是用户可见的；单图片失败不阻塞其他图片
            imageAsset.markFailed("向量化失败: " + reasonOf(e));
            imageAssetRepository.update(imageAsset);
            log.error("图片向量化失败: {} ({})", imageAsset.getFilename(), imageId, e);
        }
    }

    /**
     * 描述路向量化：标注文本（"主题。描述"）→ TEXT 模态 provider 编码 → 构造向量条目。
     * 落位 (source_type='image', model_key=text-embedding-*) 空间：与 CLIP 向量
     * 同 resourceId、同 chunkIndex=0、不同 modelKey——空间隔离闸门按
     * (sourceType, modelKey) 二元组区分，两行互不污染。只造条目不落库，
     * 由调用方与像素路合并成一次 save（见 vectorize 步骤④）。
     */
    private VectorEntry embedDescription(ImageAsset imageAsset) {
        EmbeddingProvider textProvider = providerRegistry.require(EmbeddingModality.TEXT);
        String annotationText = imageAsset.annotationText();
        List<Embedding> embedded = textProvider.embedBatch(Collections.singletonList(
                new TextDocumentResource("query", 0, annotationText)));
        if (embedded.size() != 1) {
            throw EngineException.downstream("描述路返回向量数与输入不符（期望 1 实得 "
                    + embedded.size() + "）", null);
        }
        Embedding embedding = embedded.get(0);
        return new VectorEntry(imageAsset.getId(), "image", 0, annotationText,
                embedding.getModelKey(), embedding.isDegraded(),
                embedding.getVector(), Instant.now());
    }

    /** 异常 → 用户可读原因（与原 vectorize 的兜底口径一致） */
    private static String reasonOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 优雅停机：等在途向量化跑完（兑现"非 daemon 线程跑完再退出"的语义） */
    @PreDestroy
    public void shutdown() {
        vectorizeExecutor.shutdown();
        try {
            if (!vectorizeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("图片向量化线程池 10 秒内未终止（仍有任务在跑）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 用例三：图片管理（与文档管理三件套对称） ----------

    /** 图片列表（上传时间倒序）。status 为 null 时返回全部 */
    public List<ImageAsset> listImages(ImageStatus status) {
        List<ImageAsset> all = imageAssetRepository.findAll();
        if (status == null) {
            return all;
        }
        List<ImageAsset> filtered = new ArrayList<>();
        for (ImageAsset imageAsset : all) {
            if (imageAsset.getStatus() == status) {
                filtered.add(imageAsset);
            }
        }
        return filtered;
    }

    /** 图片详情聚合：元数据 + 模型/维度/降级原因（对照 getDocumentDetail） */
    public ImageDetail getImageDetail(String id) {
        ImageAsset imageAsset = imageAssetRepository.findById(id)
                .orElseThrow(() -> EngineException.notFound("图片不存在: " + id));

        EmbeddingProvider clipProvider = providerRegistry.require(EmbeddingModality.CROSS);
        String degradedReason = null;
        if (imageAsset.isDegraded()) {
            degradedReason = visionStatus.visionStatus().isDegraded()
                    ? "CLIP 引擎降级运行: " + visionStatus.visionStatus().getLastError()
                    : "摄取时 CLIP 处于降级模式（哈希兜底向量），当前引擎已恢复";
        }
        return new ImageDetail(imageAsset,
                clipProvider.modelKey(), clipProvider.dimension(), degradedReason);
    }

    /** 删除图片：三处联动清理（SQLite 行+向量行 → 内存索引 → 原件文件）+ 缓存失效 */
    public void deleteImage(String id) {
        ImageAsset imageAsset = imageAssetRepository.findById(id)
                .orElseThrow(() -> EngineException.notFound("图片不存在: " + id));
        imageAssetRepository.deleteById(id);   // image_asset + vector_entries 图片行（级联事务）
        vectorStore.deleteByDocument(id);      // 表行已级联删，这里清内存索引（幂等双删无害）
        try {
            Files.deleteIfExists(Paths.get(imageAsset.getStoredPath()));
        } catch (IOException e) {
            log.warn("图片原件删除失败（不影响数据一致性）: {}", imageAsset.getStoredPath(), e);
        }
        // spec 场景"删除后不再命中"：缓存里的旧命中必须作废
        eventPublisher.publishEvent(new SearchCacheInvalidationEvent(
                id, SearchCacheInvalidationEvent.ChangeType.DELETED));
        log.info("图片删除完成: {} ({})", imageAsset.getFilename(), id);
    }
}
