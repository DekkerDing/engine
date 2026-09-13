package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.application.dto.ImageDetail;
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
import io.github.dekkerding.engine.domain.repository.ImageAssetRepository;
import io.github.dekkerding.engine.infrastructure.persistence.DatabaseMigrator;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteImageAssetRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteVectorStore;
import io.github.dekkerding.engine.interfaces.rest.dto.ImageSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * 图片上传用例测试 —— 原任务 3.2 + photo-semantic-search 任务 3.1/3.2 的验收：
 * 上传受理（校验三连拒）→ 异步摄取管线（①标注 → ②CLIP 像素路 → ③描述路）→
 * COMPLETED；无标注 = 恰一条 image 向量（旧行为），有标注 = 双向量分空间、
 * 标注/描述路失败均不阻塞入库、caption 透传。
 *
 * <p>【测试策略】与 DocumentApplicationServiceTest 同款：CLIP/文本/标注三个
 * 依赖全用可控替身，SQLite（含 V7 迁移）用真实件——被测的是图片管线的编排逻辑。
 */
class ImageApplicationServiceTest {

    @TempDir
    Path tempDir;

    // ---------- 测试替身 ----------

    /** 可控的 CLIP 替身：fail=模拟 CLIP 引擎不可达；degraded=模拟哈希兜底 */
    private static class StubClipProvider implements EmbeddingProvider {
        boolean fail;
        boolean degraded;

        @Override
        public EmbeddingModality modality() {
            return EmbeddingModality.CROSS;
        }

        @Override
        public String modelKey() {
            return "stub-clip";
        }

        @Override
        public int dimension() {
            return 4;
        }

        @Override
        public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            if (fail) {
                throw EngineException.downstream("CLIP 引擎不可达（模拟调用超时）", null);
            }
            List<Embedding> result = new ArrayList<>();
            for (VectorableResource resource : resources) {
                // 确定性向量：图片资源按路径首字符分桶（测试探针可撞同一桶）
                float[] vector = new float[4];
                vector[resource.asText().length() % 4] = 1f;
                result.add(new Embedding(vector, "stub-clip", degraded));
            }
            return result;
        }
    }

    /** 可控的文本引擎替身（描述路）：fail=模拟 bge 不可达 */
    private static class StubTextProvider implements EmbeddingProvider {
        boolean fail;

        @Override
        public EmbeddingModality modality() {
            return EmbeddingModality.TEXT;
        }

        @Override
        public String modelKey() {
            return "stub-text";
        }

        @Override
        public int dimension() {
            return 4;
        }

        @Override
        public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
            if (fail) {
                throw EngineException.downstream("文本引擎不可达（模拟描述路失败）", null);
            }
            List<Embedding> result = new ArrayList<>();
            for (VectorableResource resource : resources) {
                float[] vector = new float[4];
                vector[resource.asText().length() % 4] = 1f;
                result.add(new Embedding(vector, "stub-text", false));
            }
            return result;
        }
    }

    /** 可控的标注替身：fail=模拟 vlm 槽位抛错；annotation=预设产出（默认"未拆分"空标注） */
    private static class StubAnnotator implements AnnotationProvider {
        boolean fail;
        ImageAnnotation annotation = new ImageAnnotation("", "", Collections.<String>emptyList(), true);
        String capturedPath;
        String capturedFilename;
        String capturedCaption;

        @Override
        public ImageAnnotation annotate(String imagePath, String filename, String caption) {
            this.capturedPath = imagePath;
            this.capturedFilename = filename;
            this.capturedCaption = caption;
            if (fail) {
                throw EngineException.downstream("vlm 槽位不可用（模拟标注失败）", null);
            }
            return annotation;
        }
    }

    // ---------- 真实组件 + 被测服务 ----------

    private SqliteConnectionManager connectionManager;
    private SqliteVectorStore vectorStore;
    private ImageAssetRepository imageAssetRepository;
    private StubClipProvider clipProvider;
    private StubTextProvider textProvider;
    private StubAnnotator annotator;
    private ImageApplicationService service;

    @BeforeEach
    void 装配() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate(); // V1..V7（V7 = 标注五列）
        imageAssetRepository = new SqliteImageAssetRepository(connectionManager);
        vectorStore = new SqliteVectorStore(connectionManager);
        vectorStore.preload();

        clipProvider = new StubClipProvider();
        textProvider = new StubTextProvider();
        annotator = new StubAnnotator();
        service = new ImageApplicationService(
                imageAssetRepository,
                vectorStore,
                new EmbeddingProviderRegistry(Arrays.asList(clipProvider, textProvider)),
                Optional.of(annotator),
                Optional.<VisionStatusQuery>of(() -> new io.github.dekkerding.engine.domain.model.engine.EngineStatus(
                        true, clipProvider.degraded, "stub-channel",
                        Arrays.asList("stub-clip"), 4,
                        clipProvider.degraded ? "真实模型加载失败，哈希兜底" : null)),
                tempDir.resolve("images").toString(),
                20L * 1024 * 1024,
                event -> { /* 哑事件总线：单测环境无 Spring 容器 */ });
    }

    @AfterEach
    void 收尾() {
        service.shutdown();
    }

    // ---------- 测试数据 ----------

    /** 最小合法 JPEG：魔数 FF D8 FF + JFIF 头填充 */
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16,
                'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
    }

    /** 轮询等待图片到达终态（异步向量化在单线程池里跑，测试必须等它完成） */
    private ImageAsset awaitTerminal(String imageId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Optional<ImageAsset> found = imageAssetRepository.findById(imageId);
            if (found.isPresent() && found.get().getStatus().isTerminal()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        fail("图片未在 15 秒内到达终态: " + imageId);
        return null;
    }

    // ---------- 任务 3.2 主链路：上传 → 恰一向量 → COMPLETED ----------

    @Test
    void 上传jpg_到达COMPLETED且恰一条image向量() throws Exception {
        ImageAsset uploaded = service.upload("红塔.jpg", jpegBytes());
        assertEquals(ImageStatus.PENDING, uploaded.getStatus());

        ImageAsset done = awaitTerminal(uploaded.getId());

        assertEquals(ImageStatus.COMPLETED, done.getStatus(),
                "预期成功终态，实际: " + done.getStatus() + " / " + done.getErrorMessage());
        assertFalse(done.isDegraded());

        // 标注阶段已执行但产出"未拆分"（默认替身为空标注）：mocked=true 显式透传，不走描述路
        assertEquals(Boolean.TRUE, done.getAnnotationMocked(), "mock 标注器产出必须显式带 mocked 标志");
        assertFalse(done.isAnnotated(), "空标注 = 未拆分，不触发描述路向量化");

        // spec 核心断言（旧行为回归）：未标注时一图一向量，模态 image、模型空间 stub-clip
        assertEquals(1, vectorStore.count(), "未标注时一图必须恰好一条向量");
        float[] probe = new float[4];
        probe[uploaded.getStoredPath().length() % 4] = 1f;
        List<?> hits = vectorStore.search(probe, 10, "image", "stub-clip");
        assertEquals(1, hits.size(), "image 空间检索应命中该图片");
        assertEquals("红塔.jpg", ((io.github.dekkerding.engine.domain.model.vector.VectorHit) hits.get(0))
                .getEntry().getText(), "文件名随向量冗余入库");
    }

    @Test
    void 上传受理即落盘_原件可追溯() throws Exception {
        service.upload("小花.png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});

        try (java.util.stream.Stream<Path> files = Files.list(tempDir.resolve("images"))) {
            assertEquals(1, files.count(), "上传受理必须立即落盘（同步路径）");
        }
        ImageAsset asset = imageAssetRepository.findAll().get(0);
        assertTrue(Files.exists(java.nio.file.Paths.get(asset.getStoredPath())), "storedPath 指向真实文件");
        assertTrue(asset.getStoredPath().endsWith(".png"), "落盘用规范化扩展名");
    }

    // ---------- 校验三连拒（spec：不建档、不落盘） ----------

    @Test
    void 假扩展名被拒_不建档不落盘() {
        // spec 场景：.exe 改名 .jpg——MZ 头过不了 JPEG 魔数
        EngineException e = assertThrows(EngineException.class,
                () -> service.upload("病毒.jpg", new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0}));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("魔数"), "错误信息应指出魔数校验失败: " + e.getMessage());
        assertTrue(imageAssetRepository.findAll().isEmpty(), "被拒上传不得产生图片记录");
        assertFalse(Files.exists(tempDir.resolve("images")), "被拒上传不得落盘");
    }

    @Test
    void 白名单外格式被拒() {
        assertEquals(400, assertThrows(EngineException.class,
                () -> service.upload("视频.mp4", new byte[64])).getCode());
        assertTrue(imageAssetRepository.findAll().isEmpty());
    }

    @Test
    void 超限文件被拒_错误信息带上限值() {
        ImageApplicationService strict = new ImageApplicationService(
                imageAssetRepository, vectorStore,
                new EmbeddingProviderRegistry(Arrays.asList(clipProvider, textProvider)),
                Optional.of(annotator),
                Optional.<VisionStatusQuery>of(
                        () -> io.github.dekkerding.engine.domain.model.engine.EngineStatus.down("stub", null)),
                tempDir.resolve("images-strict").toString(),
                10, event -> { });
        try {
            EngineException e = assertThrows(EngineException.class,
                    () -> strict.upload("超大.jpg", jpegBytes()));
            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("上限"), "错误信息应说明大小上限: " + e.getMessage());
            assertTrue(imageAssetRepository.findAll().isEmpty(), "被拒上传不得产生图片记录");
        } finally {
            strict.shutdown();
        }
    }

    // ---------- 降级与失败路径 ----------

    @Test
    void 降级模式_资产与向量都带降级标记() throws Exception {
        clipProvider.degraded = true; // 模拟 CLIP 真实模型加载失败、哈希兜底

        ImageAsset uploaded = service.upload("降级.jpg", jpegBytes());
        ImageAsset done = awaitTerminal(uploaded.getId());

        assertEquals(ImageStatus.COMPLETED, done.getStatus(), "spec：降级仍完成摄取（INDEXED）");
        assertTrue(done.isDegraded(), "spec：资产必须显式携带 degraded 标志");

        float[] probe = new float[4];
        probe[uploaded.getStoredPath().length() % 4] = 1f;
        assertTrue(vectorStore.search(probe, 10, "image", "stub-clip").get(0).getEntry().isDegraded(),
                "降级标志必须随向量条目透传到检索结果");
    }

    @Test
    void CLIP失败进入FAILED_原因可见() throws Exception {
        clipProvider.fail = true;

        ImageAsset uploaded = service.upload("会失败.jpg", jpegBytes());
        ImageAsset done = awaitTerminal(uploaded.getId());

        assertEquals(ImageStatus.FAILED, done.getStatus());
        assertNotNull(done.getErrorMessage());
        assertTrue(done.getErrorMessage().contains("不可达"), "失败原因应含下游信息: " + done.getErrorMessage());
        assertEquals(0, vectorStore.count(), "失败管线不得留下向量");
    }

    @Test
    void 单图片失败不阻塞其他图片() throws Exception {
        clipProvider.fail = true;
        ImageAsset failing = service.upload("第一张失败.jpg", jpegBytes());
        // 先等第一张到终态再恢复开关——避免"submit 后立刻改 flag"与单线程 worker 的执行竞态
        assertEquals(ImageStatus.FAILED, awaitTerminal(failing.getId()).getStatus());

        clipProvider.fail = false; // 第二张恢复正常——证明失败的是"那张图"不是"管线"
        ImageAsset healthy = service.upload("第二张成功.jpg", jpegBytes());
        assertEquals(ImageStatus.COMPLETED, awaitTerminal(healthy.getId()).getStatus());
    }

    // ---------- photo-semantic-search 任务 3.1/3.2：标注阶段 + 描述路双向量 ----------

    @Test
    void 标注成功_双向量分空间入库_caption透传() throws Exception {
        annotator.annotation = new ImageAnnotation("花", "红色的小花",
                Arrays.asList("花", "红色"), true);

        ImageAsset uploaded = service.upload("IMG_20240101_123456.jpg", jpegBytes(), "红色的小花");
        ImageAsset done = awaitTerminal(uploaded.getId());

        assertEquals(ImageStatus.COMPLETED, done.getStatus(),
                "预期成功终态，实际: " + done.getStatus() + " / " + done.getErrorMessage());

        // 标注字段落库（spec：主题/描述/标签结构化拆分 + mocked=true 显式透传）
        assertEquals("花", done.getSubject());
        assertEquals("红色的小花", done.getDescription());
        assertEquals(Arrays.asList("花", "红色"), done.getTags());
        assertEquals(Boolean.TRUE, done.getAnnotationMocked());
        assertNull(done.getAnnotationError());
        assertEquals("花。红色的小花", done.annotationText(), "拼接形态 = 主题。描述");

        // caption 透传给标注器（覆盖描述优先于文件名派生）；路径为落盘绝对路径
        assertEquals("红色的小花", annotator.capturedCaption);
        assertTrue(annotator.capturedPath.endsWith(".jpg"), "标注器收到的是落盘路径");

        // 双向量分空间（design D2）：像素路 (image, stub-clip) + 描述路 (image, stub-text)
        assertEquals(2, vectorStore.count(), "有标注时一图两向量");

        float[] clipProbe = new float[4];
        clipProbe[uploaded.getStoredPath().length() % 4] = 1f;
        List<?> clipHits = vectorStore.search(clipProbe, 10, "image", "stub-clip");
        assertEquals(1, clipHits.size(), "像素路向量仍在 (image, clip) 空间可命中");
        assertEquals("IMG_20240101_123456.jpg",
                ((io.github.dekkerding.engine.domain.model.vector.VectorHit) clipHits.get(0))
                        .getEntry().getText());

        String annotationText = "花。红色的小花";
        float[] textProbe = new float[4];
        textProbe[annotationText.length() % 4] = 1f;
        List<?> textHits = vectorStore.search(textProbe, 10, "image", "stub-text");
        assertEquals(1, textHits.size(), "描述路向量必须在 (image, 文本引擎) 空间可命中");
        assertEquals(annotationText,
                ((io.github.dekkerding.engine.domain.model.vector.VectorHit) textHits.get(0))
                        .getEntry().getText(),
                "VectorEntry.text 冗余标注文本（重排阶段的取文本来源）");
    }

    @Test
    void 标注失败_不阻塞入库_原因落annotationError() throws Exception {
        annotator.fail = true; // 模拟 vlm 槽位不可用

        ImageAsset uploaded = service.upload("无标注.jpg", jpegBytes());
        ImageAsset done = awaitTerminal(uploaded.getId());

        // spec：标注失败不阻塞入库——像素路照常，图片仍可检索
        assertEquals(ImageStatus.COMPLETED, done.getStatus(),
                "标注失败不得让图片进 FAILED，实际: " + done.getStatus() + " / " + done.getErrorMessage());
        assertFalse(done.isAnnotated());
        assertNull(done.getAnnotationMocked(), "失败 = 回到未拆分态（NULL ≠ mock）");
        assertNotNull(done.getAnnotationError());
        assertTrue(done.getAnnotationError().contains("vlm"),
                "annotationError 应记录标注失败原因: " + done.getAnnotationError());

        // 像素路向量不受影响；描述路未产生（无标注可编码）
        assertEquals(1, vectorStore.count(), "仅像素路一条向量");
        float[] probe = new float[4];
        probe[uploaded.getStoredPath().length() % 4] = 1f;
        assertEquals(1, vectorStore.search(probe, 10, "image", "stub-clip").size(),
                "image 空间仍可命中该图片");
    }

    @Test
    void 描述路向量化失败_像素路与标注字段保留() throws Exception {
        annotator.annotation = new ImageAnnotation("花", "红色的小花",
                Arrays.asList("花"), true);
        textProvider.fail = true; // 模拟文本引擎不可达

        ImageAsset uploaded = service.upload("只像素路.jpg", jpegBytes());
        ImageAsset done = awaitTerminal(uploaded.getId());

        // spec：任一路失败保持另一路可用——描述路失败不进 FAILED
        assertEquals(ImageStatus.COMPLETED, done.getStatus(),
                "描述路失败不得让图片进 FAILED，实际: " + done.getStatus() + " / " + done.getErrorMessage());
        assertTrue(done.isAnnotated(), "标注字段保留（语义身份不因描述路失败而抹掉）");
        assertEquals("花", done.getSubject());
        assertEquals(Boolean.TRUE, done.getAnnotationMocked());
        assertNotNull(done.getAnnotationError());
        assertTrue(done.getAnnotationError().contains("描述路"),
                "失败原因应记入 annotationError 供详情页追溯: " + done.getAnnotationError());

        assertEquals(1, vectorStore.count(), "仅像素路向量入库");
        float[] probe = new float[4];
        probe[uploaded.getStoredPath().length() % 4] = 1f;
        assertEquals(1, vectorStore.search(probe, 10, "image", "stub-clip").size(),
                "像素路向量仍可检索");
    }

    @Test
    void 列表与详情DTO透出标注字段_未拆分为null占位() throws Exception {
        // 未拆分图：标注失败态（annotationMocked=NULL——与存量图的库内 NULL 同态）
        annotator.fail = true;
        ImageAsset plain = service.upload("存量图.jpg", jpegBytes());
        awaitTerminal(plain.getId());
        annotator.fail = false;

        annotator.annotation = new ImageAnnotation("花", "红色的小花",
                Arrays.asList("花", "红色"), true);
        ImageAsset annotated = service.upload("标注图.jpg", jpegBytes(), "红色的小花");
        awaitTerminal(annotated.getId());

        // 详情 DTO（ImageDetailResponse 内嵌 ImageSummary）：标注字段透出
        io.github.dekkerding.engine.interfaces.rest.dto.ImageDetailResponse detail =
                new io.github.dekkerding.engine.interfaces.rest.dto.ImageDetailResponse(
                        service.getImageDetail(annotated.getId()));
        assertEquals("花", detail.getImage().getSubject());
        assertEquals("红色的小花", detail.getImage().getDescription());
        assertEquals(Arrays.asList("花", "红色"), detail.getImage().getTags());
        assertEquals(Boolean.TRUE, detail.getImage().getAnnotationMocked());
        assertNull(detail.getImage().getAnnotationError());

        // 列表 DTO：未拆分图的占位语义（三态 null ≠ mock；spec：存量图 NULL 占位）
        ImageSummary summaryOfPlain = null;
        for (ImageAsset asset : service.listImages(null)) {
            if (asset.getId().equals(plain.getId())) {
                summaryOfPlain = new ImageSummary(asset);
            }
        }
        assertNotNull(summaryOfPlain, "未拆分图必须在列表中");
        assertEquals("", summaryOfPlain.getSubject());
        assertNull(summaryOfPlain.getAnnotationMocked(), "未拆分 = null（三态占位，非 mock）");
        assertTrue(summaryOfPlain.getTags().isEmpty());
    }

    // ---------- 任务 3.3：图片管理 ----------

    @Test
    void 删除图片_向量原件记录三处联动清理_检索不再命中() throws Exception {
        ImageAsset keep = service.upload("保留.jpg", jpegBytes());
        ImageAsset drop = service.upload("删除.jpg", jpegBytes());
        awaitTerminal(keep.getId());
        awaitTerminal(drop.getId());
        assertEquals(2, vectorStore.count());

        service.deleteImage(drop.getId());

        // 1) 向量：图片空间检索不再命中（spec 场景"删除后不再命中"）
        float[] probe = new float[4];
        probe[drop.getStoredPath().length() % 4] = 1f;
        assertTrue(vectorStore.search(probe, 10, "image", "stub-clip").isEmpty()
                        || vectorStore.count() == 1,
                "被删图片的向量必须从检索中消失");
        assertEquals(1, vectorStore.count(), "只剩保留图片的一条向量");
        // 2) 记录：详情 404
        assertEquals(404, assertThrows(EngineException.class,
                () -> service.getImageDetail(drop.getId())).getCode());
        // 3) 原件：目录里只剩保留图片的文件
        try (java.util.stream.Stream<Path> files = Files.list(tempDir.resolve("images"))) {
            assertEquals(1, files.count());
        }
        // 保留图片不受影响
        assertNotNull(service.getImageDetail(keep.getId()));
    }

    @Test
    void 删除不存在的图片返回404() {
        assertEquals(404, assertThrows(EngineException.class,
                () -> service.deleteImage("no-such-id")).getCode());
    }

    @Test
    void 列表按状态过滤() throws Exception {
        ImageAsset healthy = service.upload("完成.jpg", jpegBytes());
        assertEquals(ImageStatus.COMPLETED, awaitTerminal(healthy.getId()).getStatus(),
                "先等第一张到终态再切换替身开关——避免与单线程 worker 的执行竞态");

        clipProvider.fail = true;
        ImageAsset failing = service.upload("失败.jpg", jpegBytes());
        assertEquals(ImageStatus.FAILED, awaitTerminal(failing.getId()).getStatus());

        assertEquals(2, service.listImages(null).size());
        assertEquals(1, service.listImages(ImageStatus.COMPLETED).size());
        assertEquals(1, service.listImages(ImageStatus.FAILED).size());
        assertEquals("失败.jpg", service.listImages(ImageStatus.FAILED).get(0).getFilename());
    }

    @Test
    void 详情聚合模型信息_未降级时原因为空() throws Exception {
        ImageAsset uploaded = service.upload("详情.jpg", jpegBytes());
        awaitTerminal(uploaded.getId());

        ImageDetail detail = service.getImageDetail(uploaded.getId());

        assertEquals("stub-clip", detail.getModelKey());
        assertEquals(4, detail.getDimension());
        assertNull(detail.getDegradedReason());
        assertEquals("详情.jpg", detail.getImageAsset().getFilename());
    }

    @Test
    void 详情降级原因_降级时聚合引擎状态() throws Exception {
        clipProvider.degraded = true;
        ImageAsset uploaded = service.upload("降级详情.jpg", jpegBytes());
        awaitTerminal(uploaded.getId());

        ImageDetail detail = service.getImageDetail(uploaded.getId());

        assertTrue(detail.getImageAsset().isDegraded());
        assertNotNull(detail.getDegradedReason());
        assertTrue(detail.getDegradedReason().contains("哈希兜底"));
    }
}
