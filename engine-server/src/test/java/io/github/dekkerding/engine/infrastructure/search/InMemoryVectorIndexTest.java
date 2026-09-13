package io.github.dekkerding.engine.infrastructure.search;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存向量索引单测 —— 任务 2.2 的验收点：
 * 排序正确性、topK 截断、模态过滤、维度闸门（不一致抛明确错误）、删除联动。
 *
 * <p>【测试向量设计】用 4 维手造向量代替 512 维模型输出：方向关系一眼可读，
 * 期望分数可以手算——测试自己不应该需要一个"更可信的实现"来对答案。
 */
class InMemoryVectorIndexTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryVectorIndex index = new InMemoryVectorIndex();

    private VectorEntry entry(String docId, int chunkIndex, String text, float[] vector) {
        return new VectorEntry(docId, "text", chunkIndex, text, "text-embedding-zh", false, vector, NOW);
    }

    /** 构造 n 维单位向量（首维=1）：全零 new float[n] 会被零向量防线拒绝 */
    private float[] axis(int dim) {
        float[] v = new float[dim];
        v[0] = 1f;
        return v;
    }

    @Test
    void 按余弦降序排列() {
        index.replace("doc-a", Arrays.asList(
                entry("doc-a", 0, "同向块", new float[]{1, 0, 0, 0}),
                entry("doc-a", 1, "正交块", new float[]{0, 1, 0, 0})));
        index.replace("doc-b", Collections.singletonList(
                entry("doc-b", 0, "斜向块", new float[]{0.6f, 0.8f, 0, 0})));

        List<VectorHit> hits = index.search(new float[]{1, 0, 0, 0}, 10, null);

        assertEquals(3, hits.size());
        assertEquals("doc-a", hits.get(0).getEntry().getDocumentId());
        assertEquals(0, hits.get(0).getEntry().getChunkIndex());
        assertEquals(1.0, hits.get(0).getScore(), 1e-6);   // 同向 = 余弦 1
        assertEquals("doc-b", hits.get(1).getEntry().getDocumentId());
        assertEquals(0.6, hits.get(1).getScore(), 1e-6);
        assertEquals(0.0, hits.get(2).getScore(), 1e-6);   // 正交 = 余弦 0
    }

    @Test
    void topK截断() {
        index.replace("doc-a", Arrays.asList(
                entry("doc-a", 0, "块零", new float[]{1, 0, 0, 0}),
                entry("doc-a", 1, "块一", new float[]{0, 1, 0, 0})));

        List<VectorHit> hits = index.search(new float[]{1, 0, 0, 0}, 1, null);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).getEntry().getChunkIndex()); // 留下的是分数最高的
    }

    @Test
    void 未归一化输入_分数仍是余弦() {
        // 自防御归一化：[3,0,0,0] 模长是 3，但方向与查询一致 → 余弦应为 1 而不是 3
        index.replace("doc-a", Collections.singletonList(
                entry("doc-a", 0, "未归一化", new float[]{3, 0, 0, 0})));

        List<VectorHit> hits = index.search(new float[]{1, 0, 0, 0}, 10, null);

        assertEquals(1, hits.size());
        assertEquals(1.0, hits.get(0).getScore(), 1e-6);
    }

    @Test
    void 按模态过滤_sourceType() {
        index.replace("doc-a", Arrays.asList(
                new VectorEntry("doc-a", "text", 0, "文本块", "text-embedding-zh",
                        false, new float[]{1, 0, 0, 0}, NOW),
                new VectorEntry("doc-a", "image", 0, "图片块", "clip",
                        false, new float[]{0, 1, 0, 0}, NOW)));

        assertEquals(1, index.search(new float[]{1, 0, 0, 0}, 10, "text").size());
        assertEquals(1, index.search(new float[]{0, 1, 0, 0}, 10, "image").size());
        // null = 全库混检（CLIP 跨模态预留的接缝就在这里生效）
        assertEquals(2, index.search(new float[]{1, 0, 0, 0}, 10, null).size());
    }

    @Test
    void 维度闸门_不一致抛400且带修复指引() {
        index.replace("doc-a", Collections.singletonList(
                entry("doc-a", 0, "四维块", axis(4))));

        EngineException e = assertThrows(EngineException.class,
                () -> index.search(new float[]{1, 0, 0}, 10, null)); // 3 维查询 vs 4 维库存

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("维度"), "错误信息应说明维度冲突: " + e.getMessage());
        assertTrue(e.getMessage().contains("重新摄取"), "错误信息应给出修复动作: " + e.getMessage());
    }

    @Test
    void 混合维度库_同维子集内正常检索() {
        // 双模型切换的真实场景：库里同时有 512(bge) 与 384(MiniLM) 的向量
        index.replace("doc-bge", Collections.singletonList(
                entry("doc-bge", 0, "512维", axis(512))));
        index.replace("doc-minilm", Collections.singletonList(
                entry("doc-minilm", 0, "384维", axis(384))));

        assertEquals(1, index.search(axis(512), 10, null).size());
        assertEquals(1, index.search(axis(384), 10, null).size());
    }

    @Test
    void 空库返回空结果而非报错() {
        assertTrue(index.search(axis(4), 10, null).isEmpty());
    }

    @Test
    void 删除文档后不再命中() {
        index.replace("doc-a", Collections.singletonList(
                entry("doc-a", 0, "将被删除", new float[]{1, 0, 0, 0})));
        assertEquals(1, index.size());

        index.remove("doc-a");

        assertEquals(0, index.size());
        assertTrue(index.search(new float[]{1, 0, 0, 0}, 10, null).isEmpty());
    }

    @Test
    void 同文档重写是替换不是追加() {
        index.replace("doc-a", Arrays.asList(
                entry("doc-a", 0, "旧块零", new float[]{1, 0, 0, 0}),
                entry("doc-a", 1, "旧块一", new float[]{0, 1, 0, 0})));
        index.replace("doc-a", Collections.singletonList(
                entry("doc-a", 0, "新块零", new float[]{1, 0, 0, 0})));

        assertEquals(1, index.size()); // 旧的 2 条被整体替换，不是 2+1=3
    }

    @Test
    void 零向量拒绝入索引() {
        assertThrows(EngineException.class, () -> index.replace("doc-a",
                Collections.singletonList(entry("doc-a", 0, "零向量", new float[4]))));
    }

    // ==================== 空间闸门（任务 2.2：维度闸门 → (sourceType, modelKey) 闸门） ====================

    /** 图片条目：sourceType=image、modelKey=clip——与 bge 文本向量同维（512）异空间的活教材 */
    private VectorEntry imageEntry(String imageId, String fileName, float[] vector) {
        return new VectorEntry(imageId, "image", 0, fileName, "clip", false, vector, NOW);
    }

    /** 混合库：text/bge(512 维) + image/clip(512 维)——纯维度闸门会放行跨空间比较的复现场景 */
    private void loadMixedLibrary() {
        index.replace("doc-a", Collections.singletonList(
                entry("doc-a", 0, "红塔文本块", axis(512))));
        index.replace("img-1", Collections.singletonList(
                imageEntry("img-1", "红塔.jpg", axis(512))));
    }

    @Test
    void 混合库同维异空间_双向检索互不越界() {
        loadMixedLibrary();

        // 文本检索（text/bge 空间）：只命中文本块，图片向量绝不参与打分
        List<VectorHit> textHits = index.search(axis(512), 10, "text", "text-embedding-zh");
        assertEquals(1, textHits.size());
        assertEquals("text", textHits.get(0).getEntry().getSourceType());

        // 图片检索（image/clip 空间）：只命中图片条目，文本向量绝不参与打分
        List<VectorHit> imageHits = index.search(axis(512), 10, "image", "clip");
        assertEquals(1, imageHits.size());
        assertEquals("image", imageHits.get(0).getEntry().getSourceType());
        assertEquals("红塔.jpg", imageHits.get(0).getEntry().getText());
    }

    @Test
    void 目标模态无条目返回空结果_同模态模型不匹配抛400() {
        // 场景一：纯文本库检索图片空间——图片模态无条目是合法的空结果
        // （还没传过图片、或删光了图片后检索，都走这条路，绝不能报错）
        index.replace("doc-a", Collections.singletonList(
                entry("doc-a", 0, "红塔文本块", axis(512))));
        assertTrue(index.search(axis(512), 10, "image", "clip").isEmpty());

        // 场景二：同模态内模型不匹配（text 模态有 bge 条目，查询却要 MiniLM 空间）：
        // 模型切换场景 → 必须报"不能比"并给修复指引
        EngineException e = assertThrows(EngineException.class,
                () -> index.search(axis(384), 10, "text", "text-embedding-multilingual"));
        assertTrue(e.getMessage().contains("不可比"), "错误信息应说明不可比: " + e.getMessage());
        assertEquals(400, e.getCode());
    }

    @Test
    void 存量纯文本库_三参调用行为与升级前一致() {
        // 既有调用（不传 modelKey）在纯文本库上的行为是升级兼容性的硬约束
        index.replace("doc-a", Arrays.asList(
                entry("doc-a", 0, "块零", new float[]{1, 0, 0, 0}),
                entry("doc-a", 1, "块一", new float[]{0, 1, 0, 0})));

        assertEquals(2, index.search(new float[]{1, 0, 0, 0}, 10, null).size());
        assertEquals(2, index.search(new float[]{1, 0, 0, 0}, 10, null, null).size());
        assertEquals(2, index.search(new float[]{0, 1, 0, 0}, 10, "text").size());

        // 维度闸门语义保留：3 维查询 vs 4 维库存仍报错
        assertThrows(EngineException.class, () -> index.search(new float[]{1, 0, 0}, 10, null));
    }
}
