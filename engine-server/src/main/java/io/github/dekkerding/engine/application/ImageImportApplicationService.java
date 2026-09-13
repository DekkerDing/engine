package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.ImageImportTask.FileResult;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.image.ImageStatus;
import io.github.dekkerding.engine.domain.repository.ImageAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 批量导入应用服务 —— photo-semantic-search 任务 5.1/5.2（design D5）。
 *
 * <p>【用例流程】一次 POST /images/batch 背后发生的事：
 * <pre>
 * ① 请求级防线：文件数 / 总字节数超上限直接 400（整单拒绝，不半受理）
 * ② 逐文件预校验（复用 ImageApplicationService.ingest 的同步部分——白名单+魔数+大小）：
 *    合格 → 建档(PENDING)入队；不合格 → 记 REJECTED 明细，不入队不落盘
 * ③ 建内存任务返回 taskId（受理即回，不等执行）
 * ④ 执行：导入并发池（默认 2）逐张跑既有摄取管线 vectorize（标注+双向量化），
 *    终态回填任务明细——进度可见、部分失败不阻断
 * ⑤ 完成态任务保留 24h 后由清理线程移除（内存态纪律：重启丢任务不丢图片）
 * </pre>
 *
 * <p>【教学注释 · 为什么复用 vectorize 而不是复制管线】批量与单张的差别只在
 * "谁来调度"（HTTP 线程池 vs 导入并发池），管线本身（校验/标注/双路向量化/状态机）
 * 完全相同——同包包级方法复用，修改管线时两边天然一致。
 */
@Service
public class ImageImportApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ImageImportApplicationService.class);

    /** 完成态任务的保留窗口（design D5：24h——够 Flutter 端轮询收尾，也兜住内存上限） */
    private static final Duration FINISHED_RETENTION = Duration.ofHours(24);

    private final ImageApplicationService imageService;
    private final ImageAssetRepository imageAssetRepository;
    private final int maxFiles;
    private final long maxBytes;

    /** 导入任务表：taskId → 任务（内存态；完成态由 janitor 按 24h 清理） */
    private final ConcurrentHashMap<String, ImageImportTask> tasks = new ConcurrentHashMap<>();

    /** 导入并发池：并发度默认 2（可配）——千张瓶颈在 CLIP+bge CPU 编码，再高会挤压检索 */
    private final ExecutorService importExecutor;

    /** 清理线程：每小时扫一次完成态任务的过期保留 */
    private final ScheduledExecutorService janitor;

    /** 批量入参的载体（与 multipart 的 files/captions 逐位对应） */
    public static final class BatchFile {
        final String filename;
        final byte[] content;
        final String caption;

        public BatchFile(String filename, byte[] content, String caption) {
            this.filename = filename;
            this.content = content;
            this.caption = caption;
        }
    }

    /** 受理结果：任务 + 已入队/被拒清单（受理期快照，执行进度看任务端点） */
    public static final class Acceptance {
        private final ImageImportTask task;
        private final List<FileResult> rejected;

        public Acceptance(ImageImportTask task, List<FileResult> rejected) {
            this.task = task;
            this.rejected = rejected;
        }

        public ImageImportTask getTask() { return task; }
        public List<FileResult> getRejected() { return rejected; }
    }

    public ImageImportApplicationService(ImageApplicationService imageService,
                                         ImageAssetRepository imageAssetRepository,
                                         @Value("${engine.images.batch.max-files:100}") int maxFiles,
                                         @Value("${engine.images.batch.max-bytes:524288000}") long maxBytes,
                                         @Value("${engine.images.import.concurrency:2}") int concurrency) {
        this.imageService = imageService;
        this.imageAssetRepository = imageAssetRepository;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
        this.importExecutor = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "image-import-worker");
            t.setDaemon(true); // 守护：导入是后台批处理，不阻塞 JVM 退出（与单张摄取的非 daemon 对照）
            return t;
        });
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "image-import-janitor");
            t.setDaemon(true);
            return t;
        });
        this.janitor.scheduleWithFixedDelay(this::cleanupExpired,
                1, 1, TimeUnit.HOURS); // 首小时后再开始扫（启动期不该有可清任务）
        log.info("批量导入服务就绪: 单请求上限 {} 文件 / {} 字节, 导入并发度 {}",
                maxFiles, maxBytes, concurrency);
    }

    /**
     * 受理批量导入：整单防线 → 逐文件预校验 → 建任务 → 异步执行。
     *
     * @throws EngineException 400：空批次 / 文件数超上限 / 总字节超上限
     *                         （整单拒绝——半受理会让客户端误以为全部入队）
     */
    public Acceptance submitBatch(List<BatchFile> files) {
        if (files == null || files.isEmpty()) {
            throw EngineException.badRequest("批量导入必须包含至少一个文件");
        }
        if (files.size() > maxFiles) {
            throw EngineException.badRequest(String.format(
                    "单请求文件数超上限: %d > %d（请分批上传）", files.size(), maxFiles));
        }
        long totalBytes = 0;
        for (BatchFile file : files) {
            totalBytes += file.content == null ? 0 : file.content.length;
        }
        if (totalBytes > maxBytes) {
            throw EngineException.badRequest(String.format(
                    "单请求体量超上限: %d 字节 > %d 字节（%.1fMB）",
                    totalBytes, maxBytes, maxBytes / 1024.0 / 1024.0));
        }

        // ② 逐文件预校验（不合格记明细不入队——spec：47 受理 3 记因的语义）。
        //    total=入队数只有校验完才知道——先收集，再建任务落明细
        List<Accepted> accepted = new ArrayList<>();
        List<FileResult> rejected = new ArrayList<>();
        java.util.Set<String> existingKeys = existingFileKeys(); // 疑似重复提示（design D5：只提示不阻断）
        for (BatchFile file : files) {
            try {
                ImageAsset asset = imageService.ingest(file.filename, file.content, file.caption);
                accepted.add(new Accepted(asset.getId(), file.filename, file.caption,
                        existingKeys.contains(file.filename + ":" + file.content.length)));
            } catch (EngineException e) {
                rejected.add(new FileResult(
                        file.filename, ImageImportTask.FileState.REJECTED, null, e.getMessage(), false));
            }
        }

        ImageImportTask task = new ImageImportTask(UUID.randomUUID().toString(), accepted.size(), Instant.now());
        for (FileResult rejection : rejected) {
            task.addResult(rejection); // 拒绝明细受理期即可见
        }
        tasks.put(task.getId(), task);

        // ④ 异步执行：并发池上跑既有管线；终态回填任务
        for (Accepted entry : accepted) {
            importExecutor.submit(() -> {
                imageService.vectorize(entry.imageId, entry.caption);
                ImageAsset done = imageAssetRepository.findById(entry.imageId).orElse(null);
                if (done == null) {
                    task.recordOutcome(entry.filename, entry.imageId,
                            ImageImportTask.FileState.FAILED, "图片记录不存在（可能已被删除）", entry.duplicateSuspected);
                } else if (done.getStatus() == ImageStatus.COMPLETED) {
                    task.recordOutcome(entry.filename, entry.imageId,
                            ImageImportTask.FileState.COMPLETED, null, entry.duplicateSuspected);
                } else {
                    task.recordOutcome(entry.filename, entry.imageId, ImageImportTask.FileState.FAILED,
                            done.getErrorMessage() == null ? done.getStatus().name() : done.getErrorMessage(),
                            entry.duplicateSuspected);
                }
            });
        }
        log.info("批量导入受理: {} 文件 → {} 入队 / {} 拒绝, taskId={}",
                files.size(), accepted.size(), rejected.size(), task.getId());
        return new Acceptance(task, rejected);
    }

    /** 入队条目（受理与执行两阶段间的携带结构） */
    private static final class Accepted {
        final String imageId;
        final String filename;
        final String caption;
        final boolean duplicateSuspected;

        Accepted(String imageId, String filename, String caption, boolean duplicateSuspected) {
            this.imageId = imageId;
            this.filename = filename;
            this.caption = caption;
            this.duplicateSuspected = duplicateSuspected;
        }
    }

    /** 任务查询（进度轮询端点用）；完成态保留 24h，过期或不存在 → 404 */
    public ImageImportTask getTask(String taskId) {
        ImageImportTask task = tasks.get(taskId);
        if (task == null) {
            throw EngineException.notFound("导入任务不存在或已过期: " + taskId);
        }
        return task;
    }

    /** 过期清理（janitor 周期调用；包级可见便于单测直接构造时间边界） */
    void cleanupExpired() {
        cleanupBefore(Instant.now().minus(FINISHED_RETENTION));
    }

    void cleanupBefore(Instant threshold) {
        tasks.values().removeIf(t -> t.isFinished()
                && t.getFinishedAt() != null && t.getFinishedAt().isBefore(threshold));
    }

    private java.util.Set<String> existingFileKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (ImageAsset asset : imageAssetRepository.findAll()) {
            keys.add(asset.getFilename() + ":" + asset.getFileSizeBytes());
        }
        return keys;
    }

    @PreDestroy
    public void shutdown() {
        importExecutor.shutdown();
        janitor.shutdownNow();
        try {
            if (!importExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("批量导入线程池 10 秒内未终止（仍有导入在跑）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
