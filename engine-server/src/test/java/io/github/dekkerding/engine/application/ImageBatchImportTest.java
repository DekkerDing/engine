package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.ImageImportTask.FileResult;
import io.github.dekkerding.engine.application.ImageImportTask.FileState;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.image.ImageAnnotation;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.image.ImageStatus;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.repository.AnnotationProvider;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.VisionStatusQuery;
import io.github.dekkerding.engine.infrastructure.persistence.DatabaseMigrator;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteImageAssetRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 批量导入测试 —— photo-semantic-search 任务 5.1/5.2 的验收：
 * 受理防线（文件数/体量/空批次）、逐文件预校验记明细不入队（50 含 3 不合格 → 47 受理）、
 * 进度轮询递增与失败明细（文件名+原因）、疑似重复提示、完成态过期清理。
 *
 * <p>【测试策略】CLIP 替身带可控延迟（100ms/张）——让"执行中"窗口足够长，
 * 进度递增可被轮询观测到；管线其余依赖与 ImageApplicationServiceTest 同款替身。
 */
class ImageBatchImportTest {

    @TempDir
    Path tempDir;

    /** CLIP 替身：慢速（进度可观测）+ 指定文件名前缀抛错（失败明细可构造） */
    private static class SlowClipProvider implements EmbeddingProvider {
        boolean degraded;

        @Override public EmbeddingModality modality() { return EmbeddingModality.CROSS; }
        @Override public String modelKey() { return "stub-clip"; }
        @Override public int dimension() { return 4; }
        @Override public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            List<Embedding> result = new ArrayList<>();
            for (VectorableResource resource : resources) {
                // 管线传入的是 ImageAssetResource（asText=存储路径不含原名，判定用 getFileName）
                String filename = ((io.github.dekkerding.engine.domain.model.resource.ImageAssetResource) resource)
                        .getFileName();
                if (filename.contains("会失败")) {
                    throw EngineException.downstream("CLIP 引擎处理该图片失败（模拟）", null);
                }
                try {
                    Thread.sleep(100); // 拉长执行窗口，轮询能看见中间进度
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                float[] vector = new float[4];
                vector[resource.asText().length() % 4] = 1f;
                result.add(new Embedding(vector, "stub-clip", degraded));
            }
            return result;
        }
    }

    private static class StubTextProvider implements EmbeddingProvider {
        @Override public EmbeddingModality modality() { return EmbeddingModality.TEXT; }
        @Override public String modelKey() { return "stub-text"; }
        @Override public int dimension() { return 4; }
        @Override public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            List<Embedding> result = new ArrayList<>();
            for (VectorableResource r : resources) {
                float[] vector = new float[4];
                vector[r.asText().length() % 4] = 1f;
                result.add(new Embedding(vector, "stub-text", false));
            }
            return result;
        }
    }

    private static class StubAnnotator implements AnnotationProvider {
        @Override public ImageAnnotation annotate(String imagePath, String filename, String caption) {
            return new ImageAnnotation("花", "红色的小花", Collections.singletonList("花"), true);
        }
    }

    private SqliteConnectionManager connectionManager;
    private SqliteVectorStore vectorStore;
    private SqliteImageAssetRepository imageAssetRepository;
    private SlowClipProvider clipProvider;
    private ImageApplicationService imageService;
    private ImageImportApplicationService importService;

    @BeforeEach
    void 装配() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate();
        imageAssetRepository = new SqliteImageAssetRepository(connectionManager);
        vectorStore = new SqliteVectorStore(connectionManager);
        vectorStore.preload();
        clipProvider = new SlowClipProvider();
        imageService = new ImageApplicationService(
                imageAssetRepository,
                vectorStore,
                new EmbeddingProviderRegistry(Arrays.asList(clipProvider, new StubTextProvider())),
                Optional.of(new StubAnnotator()),
                Optional.<VisionStatusQuery>of(() -> new io.github.dekkerding.engine.domain.model.engine.EngineStatus(
                        true, false, "stub-channel", Collections.singletonList("stub-clip"), 4, null)),
                tempDir.resolve("images").toString(),
                20L * 1024 * 1024,
                event -> { });
        // 导入并发度 1：进度单调可断言（并发 2 时中间值可能跳跃，但递增语义相同）
        importService = new ImageImportApplicationService(
                imageService, imageAssetRepository, 100, 500L * 1024 * 1024, 1);
    }

    @AfterEach
    void 收尾() {
        importService.shutdown();
        imageService.shutdown();
    }

    // ---------- 脚手架 ----------

    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16,
                'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
    }

    /** 指定长度的合法 JPEG（补零——大小参与疑似重复判定） */
    private static byte[] jpegBytes(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(jpegBytes(), 0, bytes, 0, 20);
        return bytes;
    }

    private static byte[] badMagicBytes() {
        return new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0}; // exe 头冒充 jpg
    }

    private ImageImportApplicationService.BatchFile file(String name, byte[] content) {
        return new ImageImportApplicationService.BatchFile(name, content, null);
    }

    /** 轮询至任务完成（返回期间观测到的 processed 序列——用于递增断言） */
    private List<Integer> awaitFinished(String taskId) throws InterruptedException {
        List<Integer> observed = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            ImageImportTask task = importService.getTask(taskId);
            observed.add(task.getProcessed());
            if (task.isFinished()) {
                return observed;
            }
            Thread.sleep(20);
        }
        fail("导入任务未在 30 秒内完成: " + taskId);
        return observed;
    }

    /** 断言观测序列存在严格递增步（进度可见递增而非一次性跳变到终态） */
    private static void assertProgressIncreasing(List<Integer> observed) {
        for (int i = 1; i < observed.size(); i++) {
            if (observed.get(i) > observed.get(i - 1)) {
                return;
            }
        }
        fail("轮询未见进度递增: " + observed);
    }

    // ---------- 任务 5.1：受理防线与逐文件预校验 ----------

    @Test
    void 五十文件含三不合格_四十七受理三记因() throws Exception {
        List<ImageImportApplicationService.BatchFile> files = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            files.add(file(String.format("照片%02d.jpg", i), jpegBytes()));
        }
        files.set(4, file("伪装1.jpg", badMagicBytes()));  // 魔数不符
        files.set(19, file("视频.mp4", new byte[64]));     // 白名单外
        files.set(39, file("空.jpg", new byte[0]));        // 空内容

        ImageImportApplicationService.Acceptance acceptance = importService.submitBatch(files);

        ImageImportTask task = acceptance.getTask();
        assertEquals(47, task.getTotal(), "47 张合格文件入队（spec：50 含 3 不合格 → 47 受理）");
        assertEquals(3, acceptance.getRejected().size(), "3 张不合格在受理期即记因");
        for (FileResult rejection : acceptance.getRejected()) {
            assertEquals(FileState.REJECTED, rejection.getState());
            assertNotNull(rejection.getReason(), "拒绝明细必须含原因: " + rejection.getFilename());
            assertEquals(null, rejection.getImageId(), "被拒文件不建档");
        }

        awaitFinished(task.getId());
        assertEquals(47, task.getProcessed());
        assertEquals(47, task.getSucceeded());
        assertEquals(0, task.getFailed());
        assertEquals(3, task.getRejected());
        assertEquals(94, vectorStore.count(), "47 张 × 双向量（标注替身恒有产出）");
    }

    @Test
    void 空批次被拒() {
        EngineException e = assertThrows(EngineException.class,
                () -> importService.submitBatch(Collections.emptyList()));
        assertEquals(400, e.getCode());
    }

    @Test
    void 文件数超上限_整单拒绝() {
        ImageImportApplicationService strict = new ImageImportApplicationService(
                imageService, imageAssetRepository, 2, 500L * 1024 * 1024, 1);
        try {
            EngineException e = assertThrows(EngineException.class, () -> strict.submitBatch(Arrays.asList(
                    file("1.jpg", jpegBytes()), file("2.jpg", jpegBytes()), file("3.jpg", jpegBytes()))));
            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("文件数"), "错误信息应说明文件数上限: " + e.getMessage());
            assertTrue(imageAssetRepository.findAll().isEmpty(), "整单拒绝不产生任何图片记录");
        } finally {
            strict.shutdown();
        }
    }

    @Test
    void 体量超上限_整单拒绝() {
        ImageImportApplicationService strict = new ImageImportApplicationService(
                imageService, imageAssetRepository, 100, 30, 1);
        try {
            EngineException e = assertThrows(EngineException.class, () -> strict.submitBatch(Arrays.asList(
                    file("a.jpg", jpegBytes()), file("b.jpg", jpegBytes()))));
            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("体量"), "错误信息应说明体量上限: " + e.getMessage());
        } finally {
            strict.shutdown();
        }
    }

    // ---------- 任务 5.2：进度可见与失败明细 ----------

    @Test
    void 执行中轮询可见进度递增_失败明细含文件名与原因() throws Exception {
        ImageImportApplicationService.Acceptance acceptance = importService.submitBatch(Arrays.asList(
                file("第一张.jpg", jpegBytes()),
                file("会失败.jpg", jpegBytes()),   // CLIP 替身对该文件名抛错
                file("第二张.jpg", jpegBytes()),
                file("第三张.jpg", jpegBytes()),
                file("第四张.jpg", jpegBytes())));

        ImageImportTask task = acceptance.getTask();
        List<Integer> observed = awaitFinished(task.getId());

        assertProgressIncreasing(observed);
        assertEquals(5, task.getTotal());
        assertEquals(5, task.getProcessed());
        assertEquals(4, task.getSucceeded());
        assertEquals(1, task.getFailed());

        FileResult failed = null;
        for (FileResult r : task.getResults()) {
            if (r.getState() == FileState.FAILED) {
                failed = r;
            }
        }
        assertNotNull(failed, "管线失败必须落明细");
        assertEquals("会失败.jpg", failed.getFilename(), "失败明细含文件名（spec）");
        assertNotNull(failed.getImageId(), "失败文件已建档（进了管线才失败）");
        assertTrue(failed.getReason().contains("CLIP"), "失败明细含原因: " + failed.getReason());

        // 失败那张的状态最终为 FAILED（部分失败不阻断其他文件）
        ImageAsset failedAsset = imageAssetRepository.findById(failed.getImageId()).get();
        assertEquals(ImageStatus.FAILED, failedAsset.getStatus());
    }

    @Test
    void 同名同大小存量_明细标注疑似重复() throws Exception {
        // 预置一张同名同大小的存量图
        ImageAsset existing = new ImageAsset("img-old", "重复.jpg",
                "data/images/重复.jpg", 1024, Instant.now());
        existing.transitionTo(ImageStatus.VECTORIZING);
        existing.transitionTo(ImageStatus.COMPLETED);
        imageAssetRepository.save(existing);

        ImageImportApplicationService.Acceptance acceptance = importService.submitBatch(
                Collections.singletonList(file("重复.jpg", jpegBytes(1024))));
        ImageImportTask task = acceptance.getTask();
        awaitFinished(task.getId());

        FileResult result = task.getResults().get(0);
        assertEquals(FileState.COMPLETED, result.getState(), "疑似重复只提示不阻断（design D5）");
        assertTrue(result.isDuplicateSuspected(), "同名同大小应标注疑似重复");
    }

    @Test
    void 完成态任务过期后清理_未完成不清理() throws Exception {
        ImageImportApplicationService.Acceptance acceptance = importService.submitBatch(
                Collections.singletonList(file("小批量.jpg", jpegBytes())));
        ImageImportTask task = acceptance.getTask();
        awaitFinished(task.getId());
        assertTrue(task.isFinished());

        // 未过保留窗口：可查
        assertNotNull(importService.getTask(task.getId()));

        // 过期（阈值=完成时刻+1s，任务完成于其前）→ 清理后 404
        importService.cleanupBefore(Instant.now().plusSeconds(1));
        EngineException gone = assertThrows(EngineException.class,
                () -> importService.getTask(task.getId()));
        assertEquals(404, gone.getCode(), "过期任务按不存在处理");
    }

    @Test
    void 不存在的任务返回404() {
        assertEquals(404, assertThrows(EngineException.class,
                () -> importService.getTask("no-such-task")).getCode());
    }
}
