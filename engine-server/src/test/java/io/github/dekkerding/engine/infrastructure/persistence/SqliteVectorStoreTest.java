package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqliteVectorStore 全栈单测 —— 任务 2.1 的主验收：
 * 真实 SQLite 文件 + 真实迁移 + DB 读写往返 + 内存索引联动。
 *
 * <p>【测试策略】不起 Spring 容器：直接 new 各组件（连接管理器/迁移器/存储），
 * 单测要快、要聚焦——Spring 上下文的装配正确性留给后面的端到端验收。
 */
class SqliteVectorStoreTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connectionManager;
    private SqliteVectorStore store;

    @BeforeEach
    void 建库并迁移() {
        connectionManager = new SqliteConnectionManager(tempDir.resolve("test.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate(); // V1 建表 + V2 补 degraded 列
        store = new SqliteVectorStore(connectionManager);
        store.preload();
    }

    private VectorEntry entry(String docId, int chunkIndex, String text, float[] vector, boolean degraded) {
        return new VectorEntry(docId, "text", chunkIndex, text, "text-embedding-zh",
                degraded, vector, NOW);
    }

    @Test
    void 写入计数与检索命中() {
        store.save("doc-1", Arrays.asList(
                entry("doc-1", 0, "红塔公园的小花", new float[]{1, 0, 0, 0}, false),
                entry("doc-1", 1, "旁边的红色宝塔", new float[]{0, 1, 0, 0}, false)));

        assertEquals(2, store.count());

        List<VectorHit> hits = store.search(new float[]{1, 0, 0, 0}, 10, null, null);
        assertEquals(2, hits.size());
        assertEquals("红塔公园的小花", hits.get(0).getEntry().getText()); // 同向的排第一
        assertEquals(1.0, hits.get(0).getScore(), 1e-6);
    }

    /**
     * 任务 2.1 的核心断言：向量经 SQLite BLOB 落盘再读回，位级无损。
     * 【关键】用"新 store + preload"强制走 DB 读回路径（mapRow → BLOB decode），
     * 而不是内存里还持有的原对象引用——否则测的只是引用相等，没测到持久化。
     */
    @Test
    void float数组经BLOB落盘读回_位级无损() {
        float[] original = {0.123f, -0.456f, 0.789f, -0.123456789f};
        store.save("doc-1", Collections.singletonList(
                entry("doc-1", 0, "精度见证者", original, false)));

        SqliteVectorStore reloaded = new SqliteVectorStore(connectionManager);
        reloaded.preload(); // 模拟重启：全量从 DB 读回

        List<VectorHit> hits = reloaded.search(new float[]{1, 0, 0, 0}, 10, null, null);
        assertEquals(1, hits.size());
        assertArrayEquals(original, hits.get(0).getEntry().getVector());
        assertEquals(4, hits.get(0).getEntry().getDimension());
        assertEquals("text-embedding-zh", hits.get(0).getEntry().getModelKey());
    }

    @Test
    void 降级标志随向量持久化() {
        store.save("doc-1", Collections.singletonList(
                entry("doc-1", 0, "哈希兜底块", new float[]{1, 0, 0, 0}, true)));

        SqliteVectorStore reloaded = new SqliteVectorStore(connectionManager);
        reloaded.preload();

        List<VectorHit> hits = reloaded.search(new float[]{1, 0, 0, 0}, 10, null, null);
        assertTrue(hits.get(0).getEntry().isDegraded(), "降级标志必须如实透传到检索结果");
    }

    @Test
    void 同文档重写幂等_先清后写() {
        store.save("doc-1", Arrays.asList(
                entry("doc-1", 0, "旧块零", new float[]{1, 0, 0, 0}, false),
                entry("doc-1", 1, "旧块一", new float[]{0, 1, 0, 0}, false)));
        assertEquals(2, store.count());

        // 重传（重试场景）：3 条新向量替换 2 条旧向量，不能变成 5
        store.save("doc-1", Arrays.asList(
                entry("doc-1", 0, "新块零", new float[]{1, 0, 0, 0}, false),
                entry("doc-1", 1, "新块一", new float[]{0, 1, 0, 0}, false),
                entry("doc-1", 2, "新块二", new float[]{0, 0, 1, 0}, false)));

        assertEquals(3, store.count());
        assertEquals(3, store.search(new float[]{1, 0, 0, 0}, 10, null, null).size());
    }

    @Test
    void 删除文档_库与索引联动清理() {
        store.save("doc-1", Collections.singletonList(
                entry("doc-1", 0, "独有内容", new float[]{1, 0, 0, 0}, false)));
        store.save("doc-2", Collections.singletonList(
                entry("doc-2", 0, "保留内容", new float[]{0.6f, 0.8f, 0, 0}, false)));

        store.deleteByDocument("doc-1");

        assertEquals(1, store.count());
        List<VectorHit> hits = store.search(new float[]{1, 0, 0, 0}, 10, null, null);
        assertEquals(1, hits.size());
        assertEquals("doc-2", hits.get(0).getEntry().getDocumentId());
    }

    @Test
    void 重启后检索能力恢复() {
        store.save("doc-1", Collections.singletonList(
                entry("doc-1", 0, "重启前写入", new float[]{1, 0, 0, 0}, false)));

        // 新 store 实例 = 模拟进程重启（内存索引清空，只靠 preload 从 DB 重建）
        SqliteVectorStore restarted = new SqliteVectorStore(connectionManager);
        restarted.preload();

        List<VectorHit> hits = restarted.search(new float[]{1, 0, 0, 0}, 10, null, null);
        assertEquals(1, hits.size());
        assertEquals("重启前写入", hits.get(0).getEntry().getText());
    }

    @Test
    void 跨文档检索按分数全局排序() {
        store.save("doc-a", Collections.singletonList(
                entry("doc-a", 0, "中等相似", new float[]{0.6f, 0.8f, 0, 0}, false)));
        store.save("doc-b", Collections.singletonList(
                entry("doc-b", 0, "最相似", new float[]{1, 0, 0, 0}, false)));

        List<VectorHit> hits = store.search(new float[]{1, 0, 0, 0}, 10, null, null);

        assertEquals(2, hits.size());
        assertEquals("doc-b", hits.get(0).getEntry().getDocumentId());
        assertFalse(hits.get(0).getScore() < hits.get(1).getScore(), "结果必须按分数降序");
    }

    // ==================== 图片行读写（任务 2.3：image 与 text 混存一张表，零迁移） ====================

    private VectorEntry imageEntry(String imageId, String fileName, float[] vector) {
        return new VectorEntry(imageId, "image", 0, fileName, "clip", false, vector, NOW);
    }

    @Test
    void 图片行写入_空间过滤检索_重启全量加载() {
        store.save("doc-a", Collections.singletonList(
                entry("doc-a", 0, "红塔文本块", new float[]{0, 1, 0, 0}, false)));
        store.save("img-1", Collections.singletonList(
                imageEntry("img-1", "红塔.jpg", new float[]{1, 0, 0, 0})));

        assertEquals(2, store.count(), "count 应含 text 与 image 全部条目");

        // image 空间检索命中图片行
        List<VectorHit> imageHits = store.search(new float[]{1, 0, 0, 0}, 10, "image", "clip");
        assertEquals(1, imageHits.size());
        assertEquals("红塔.jpg", imageHits.get(0).getEntry().getText());
        assertEquals("clip", imageHits.get(0).getEntry().getModelKey());

        // 重启（新 store + preload）后 image 行全量加载回内存
        SqliteVectorStore restarted = new SqliteVectorStore(connectionManager);
        restarted.preload();
        List<VectorHit> reloadedHits = restarted.search(new float[]{1, 0, 0, 0}, 10, "image", "clip");
        assertEquals(1, reloadedHits.size(), "重启后 image 行必须从 SQLite 全量恢复");
    }

    @Test
    void 按文档删除图片行_检索不再命中() {
        store.save("doc-a", Collections.singletonList(
                entry("doc-a", 0, "红塔文本块", new float[]{0, 1, 0, 0}, false)));
        store.save("img-1", Collections.singletonList(
                imageEntry("img-1", "红塔.jpg", new float[]{1, 0, 0, 0})));

        store.deleteByDocument("img-1");

        assertTrue(store.search(new float[]{1, 0, 0, 0}, 10, "image", "clip").isEmpty(),
                "删除后图片空间检索不应命中");
        assertEquals(1, store.count(), "只剩文本条目");
    }
}
