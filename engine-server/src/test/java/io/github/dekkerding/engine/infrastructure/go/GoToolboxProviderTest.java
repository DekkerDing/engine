package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoToolboxProvider 门面单测 —— 任务 2.3 的验收点：send→DTO 解析与错误翻译。
 *
 * <p>【假通道策略】不拉子进程——门面只做"组 params + 解 result"，用 JVM 内
 * 脚本化假通道（预置响应帧队列）即可全覆盖；真实多进程语义归
 * {@link StdioGoChannelTest}（假进程版）。两级测试各管一层，不重复。
 *
 * <p>【帧真实性】假通道回放的是 JSON 字符串并经 {@link GoProtocol#parseResponse}
 * 真解析——DTO 提取链路与生产完全同路径，不是手工构造的对象。
 */
class GoToolboxProviderTest {

    /** 脚本化假通道：依次回放预置 JSON 帧，错误帧按真通道同款语义转 EngineException。 */
    static class FakeGoChannel implements GoChannel {
        final Deque<String> frames = new ArrayDeque<>();
        String lastMethod;
        Map<String, Object> lastParams;

        void enqueue(String json) {
            frames.addLast(json);
        }

        @Override
        public String channelName() {
            return "fake";
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public GoProtocol.Response send(String method, Map<String, Object> params) {
            lastMethod = method;
            lastParams = params;
            String line = frames.pollFirst();
            if (line == null) {
                throw new IllegalStateException("脚本帧耗尽，未预期的调用: " + method);
            }
            GoProtocol.Response resp = GoProtocol.parseResponse(line);
            if (resp.isError()) {
                // 与 GoStdioChannel.call 同款翻译——门面测试也要贴真语义
                throw EngineException.downstream(
                        "Go 引擎错误 [code=" + resp.error.code + "]: " + resp.error.message);
            }
            return resp;
        }
    }

    private static VectorableResource textResource(String text) {
        // 最小可用匿名实现：门面只调 asText()，其余两法给占位值即可
        return new VectorableResource() {
            @Override
            public String resourceId() {
                return "test-res";
            }

            @Override
            public String sourceType() {
                return "text";
            }

            @Override
            public String asText() {
                return text;
            }
        };
    }

    @Test
    void tokenize解析() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":1,\"result\":{\"tokens\":[\"红塔\",\"塔在\",\"在公\",\"公园\"],\"count\":4,\"mode\":\"search\"}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        List<String> tokens = provider.tokenize("红塔在公园", "search");

        assertEquals("text.tokenize", fake.lastMethod);
        assertEquals("红塔在公园", fake.lastParams.get("text"));
        assertEquals("search", fake.lastParams.get("mode"));
        assertEquals(4, tokens.size());
        assertEquals("红塔", tokens.get(0));
        assertEquals("公园", tokens.get(3));
    }

    @Test
    void keywords解析() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":2,\"result\":{\"keywords\":[{\"term\":\"红塔\",\"weight\":0.9},{\"term\":\"公园\",\"weight\":0.45}]}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        List<GoProtocol.KeywordsResult.KeywordItem> kws = provider.keywords("红塔在公园", 2);

        assertEquals("text.keywords", fake.lastMethod);
        assertEquals(2, kws.size());
        assertEquals("红塔", kws.get(0).term);
        assertEquals(0.9, kws.get(0).weight, 1e-9);
        assertEquals(0.45, kws.get(1).weight, 1e-9);
    }

    @Test
    void chunk解析() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":3,\"result\":{\"chunks\":[{\"document_id\":\"d1\",\"index\":0,\"text\":\"第一块。\"},{\"document_id\":\"d1\",\"index\":1,\"text\":\"第二块。\"}],\"count\":2}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        List<GoProtocol.ChunkResult.ChunkItem> chunks = provider.chunkText("d1", "第一块。第二块。");

        assertEquals("text.chunk", fake.lastMethod);
        assertEquals("d1", fake.lastParams.get("document_id"));
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(1).index);
        assertEquals("第二块。", chunks.get(1).text);
    }

    @Test
    void generateHash解析与normalize参数() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":5,\"result\":{\"vector\":[0.5,0.5],\"dim\":2,\"degraded\":true,\"text\":\"你好\"}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        GoProtocol.HashResult hash = provider.generateHash("你好", 2, false);

        assertEquals("hashing.generate", fake.lastMethod);
        assertEquals("你好", fake.lastParams.get("text"));
        assertEquals(2, fake.lastParams.get("dim"));
        assertEquals(Boolean.FALSE, fake.lastParams.get("normalize"));
        assertEquals(2, hash.vector.size());
        assertTrue(hash.degraded, "哈希向量必须带 degraded 标志");
        assertEquals(0.5, hash.vector.get(0), 1e-9);
    }

    @Test
    void stats解析() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":6,\"result\":{\"engine\":\"gotoolbox\",\"version\":\"0.2.0\",\"vector_count\":0,\"tools\":[\"sys.ping\",\"text.chunk\"]}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        GoProtocol.StatsResult stats = provider.stats();

        assertEquals("sys.stats", fake.lastMethod);
        assertEquals("gotoolbox", stats.engine);
        assertEquals("0.2.0", stats.version);
        assertTrue(stats.tools.contains("sys.ping"));
    }

    @Test
    void embedBatch降级向量组装() {
        FakeGoChannel fake = new FakeGoChannel();
        // embedBatch 固定 dimension()=512 + normalize=true——假帧同参回放
        StringBuilder vec = new StringBuilder("[");
        for (int i = 0; i < 512; i++) {
            vec.append(i > 0 ? "," : "").append("0.04417707182364066");
        }
        vec.append("]");
        fake.enqueue("{\"id\":7,\"result\":{\"vector\":" + vec
                + ",\"dim\":512,\"degraded\":true,\"text\":\"文档一\"}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        List<Embedding> out = provider.embedBatch(
                Collections.singletonList(textResource("文档一")));

        assertEquals("hashing.generate", fake.lastMethod);
        assertEquals(512, fake.lastParams.get("dim"));
        assertEquals(Boolean.TRUE, fake.lastParams.get("normalize"));
        assertEquals(1, out.size());
        Embedding e = out.get(0);
        assertEquals("go-hash-degraded", e.getModelKey());
        assertTrue(e.isDegraded(), "降级向量必须带 degraded 标志");
        assertEquals(512, e.getVector().length);
        assertEquals(0.04417707182364066, e.getVector()[0], 1e-7);
    }

    @Test
    void 错误帧转EngineException() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":8,\"error\":{\"code\":1001,\"message\":\"方法不存在: no.such\"}}");
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        EngineException e = assertThrows(EngineException.class,
                () -> provider.tokenize("文本", "search"));
        assertTrue(e.getMessage().contains("1001"), "错误翻译应保留 code，实际: " + e.getMessage());
        assertEquals(503, e.getCode(), "下游引擎错误应映射 503");
    }

    @Test
    void 空result容错返回空集() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":9,\"result\":{}}"); // result 空对象（非 null）→ extractResult 得全默认 DTO
        GoToolboxProvider provider = new GoToolboxProvider(fake);

        List<String> tokens = provider.tokenize("文本", "search");
        assertTrue(tokens.isEmpty(), "空 result 应容错为空集而非 NPE");
    }
}
