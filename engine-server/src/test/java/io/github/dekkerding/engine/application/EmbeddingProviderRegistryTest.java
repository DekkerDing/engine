package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Provider 注册表单测 —— 任务 3.1 的核心断言：
 * 双 provider（TEXT + CROSS）并存注册、按模态路由正确、重复模态在装配期就爆。
 *
 * <p>【测试策略】不起 Spring：注册动作就是构造函数收 List，直接 new——
 * Spring 的 List 注入装配由端到端验收覆盖，这里只验表逻辑本身。
 */
class EmbeddingProviderRegistryTest {

    /** 最小 stub：只回答身份三问，embedBatch 不会被这个测试类碰到 */
    private static EmbeddingProvider provider(EmbeddingModality modality, String key) {
        return new EmbeddingProvider() {
            @Override public EmbeddingModality modality() { return modality; }
            @Override public String modelKey() { return key; }
            @Override public int dimension() { return 512; }
            @Override public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
                return Collections.emptyList();
            }
        };
    }

    @Test
    void 双provider并存_按模态路由正确() {
        EmbeddingProvider text = provider(EmbeddingModality.TEXT, "text-embedding-zh");
        EmbeddingProvider clip = provider(EmbeddingModality.CROSS, "clip");

        EmbeddingProviderRegistry registry = new EmbeddingProviderRegistry(Arrays.asList(text, clip));

        assertSame(text, registry.require(EmbeddingModality.TEXT), "文本摄取必须路由到 bge provider");
        assertSame(clip, registry.require(EmbeddingModality.CROSS), "图片摄取/跨模态查询必须路由到 CLIP provider");
        assertEquals("clip", registry.require(EmbeddingModality.CROSS).modelKey());
    }

    @Test
    void 同模态重复注册_装配期抛错() {
        // 配置漂移场景：误注册两个 TEXT provider —— 越早爆越诚实，拖到运行期
        // 才发现"文本走了错误的编码器"就是数据事故
        assertThrows(IllegalStateException.class, () -> new EmbeddingProviderRegistry(Arrays.asList(
                provider(EmbeddingModality.TEXT, "text-embedding-zh"),
                provider(EmbeddingModality.TEXT, "text-embedding-multilingual"))));
    }

    @Test
    void 缺失模态_require抛错_find返回null() {
        EmbeddingProviderRegistry registry = new EmbeddingProviderRegistry(
                Collections.singletonList(provider(EmbeddingModality.TEXT, "text-embedding-zh")));

        assertThrows(IllegalStateException.class, () -> registry.require(EmbeddingModality.CROSS));
        assertNull(registry.find(EmbeddingModality.CROSS), "可选能力：CLIP 未装配时系统仍可纯文本运行");
    }
}
