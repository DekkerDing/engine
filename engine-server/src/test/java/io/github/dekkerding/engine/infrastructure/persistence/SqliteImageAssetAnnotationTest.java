package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.model.image.ImageAnnotation;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.image.ImageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片资产语义标注持久化测试 —— photo-semantic-search 任务 2.3 的主验收：
 * V7 迁移落列、标注字段读写往返、mocked 三态（null/true/false）、存量行"未拆分"态。
 *
 * <p>【测试策略】同 SqliteVectorStoreTest：不起 Spring，直接 new 组件 + 真实 SQLite。
 */
class SqliteImageAssetAnnotationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connectionManager;
    private SqliteImageAssetRepository repository;

    @BeforeEach
    void 建库并迁移() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate(); // V1..V7
        repository = new SqliteImageAssetRepository(connectionManager);
    }

    private ImageAsset newAsset(String id, String filename) {
        return new ImageAsset(id, filename, "data/images/" + filename, 1024, NOW);
    }

    @Test
    void 标注字段落库与回读往返() {
        ImageAsset asset = newAsset("img-1", "flower.jpg");
        asset.applyAnnotation(new ImageAnnotation("红色、小花", "红色的小花",
                Arrays.asList("小花", "红色"), true));
        asset.transitionTo(ImageStatus.VECTORIZING);
        asset.transitionTo(ImageStatus.COMPLETED);
        repository.save(asset);

        Optional<ImageAsset> loaded = repository.findById("img-1");
        assertTrue(loaded.isPresent());
        ImageAsset a = loaded.get();
        assertEquals("红色、小花", a.getSubject());
        assertEquals("红色的小花", a.getDescription());
        assertEquals(Arrays.asList("小花", "红色"), a.getTags());
        assertEquals(Boolean.TRUE, a.getAnnotationMocked()); // mock 产出
        assertNull(a.getAnnotationError());
        assertTrue(a.isAnnotated());
        assertEquals("红色、小花。红色的小花", a.annotationText());
    }

    @Test
    void 真实VLM标注的mocked为false() {
        ImageAsset asset = newAsset("img-2", "tower.jpg");
        asset.applyAnnotation(new ImageAnnotation("红塔", "红塔与白云",
                Arrays.asList("红塔"), false));
        repository.save(asset);

        ImageAsset a = repository.findById("img-2").get();
        assertEquals(Boolean.FALSE, a.getAnnotationMocked()); // 三态之"真实产出"
    }

    @Test
    void 标注失败记录原因且不阻塞入库() {
        ImageAsset asset = newAsset("img-3", "bad.jpg");
        asset.markAnnotationFailed("Python 通道超时");
        repository.save(asset);

        ImageAsset a = repository.findById("img-3").get();
        assertFalse(a.isAnnotated());
        assertNull(a.getAnnotationMocked()); // 失败 = 回到未拆分态（非 mock）
        assertEquals("Python 通道超时", a.getAnnotationError());
    }

    @Test
    void 存量行迁移后为未拆分态() throws Exception {
        // 模拟 V7 前入库的存量行：save 一条后手工把标注列抹回默认值
        ImageAsset asset = newAsset("img-old", "IMG_20240101_123456.jpg");
        repository.save(asset);
        try (Connection c = connectionManager.open(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE image_asset SET subject='', description='', tags='[]'," +
                    " annotation_mocked=NULL, annotation_error=NULL WHERE id='img-old'");
        }

        ImageAsset a = repository.findById("img-old").get();
        assertFalse(a.isAnnotated());
        assertNull(a.getAnnotationMocked()); // 未拆分（NULL）≠ mocked=false，三态可区分
        assertTrue(a.getTags().isEmpty());
        assertEquals("", a.annotationText());
    }

    @Test
    void 更新时标注字段同步持久化() {
        ImageAsset asset = newAsset("img-4", "sea.jpg");
        repository.save(asset);

        asset.applyAnnotation(new ImageAnnotation("海边", "海边的日出",
                Arrays.asList("海边", "日出"), true));
        asset.transitionTo(ImageStatus.VECTORIZING);
        asset.transitionTo(ImageStatus.COMPLETED);
        repository.update(asset);

        ImageAsset a = repository.findById("img-4").get();
        assertEquals("海边的日出", a.getDescription());
        assertEquals(Boolean.TRUE, a.getAnnotationMocked());
        assertEquals(ImageStatus.COMPLETED, a.getStatus());
    }
}
