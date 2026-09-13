package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoVectorReplica 协议单测 —— 任务 4.3 的协议侧验收：
 * 复制/删除/检索/相似度的 params 组装与 result 解析（含契约键名——
 * query/inserted/removed/score 与 Go 端 engine.go 的帧字段逐字对齐）。
 *
 * <p>【假通道策略】与 {@link GoToolboxProviderTest} 同款：JVM 内脚本化假通道，
 * 回放 JSON 经 {@link GoProtocol#parseResponse} 真解析——DTO 链路与生产同路径。
 */
class GoVectorReplicaTest {

    /** 脚本化假通道（同 GoToolboxProviderTest 的语义，独立副本避免测试间耦合）。 */
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
                throw EngineException.downstream(
                        "Go 引擎错误 [code=" + resp.error.code + "]: " + resp.error.message);
            }
            return resp;
        }
    }

    private static VectorEntry entry(String docId, int chunkIdx, String modelKey, float... vector) {
        return new VectorEntry(docId, "text", chunkIdx, "块文本", modelKey,
                false, vector, Instant.now());
    }

    @Test
    void replace组装契约_vector_insert帧() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":1,\"result\":{\"inserted\":2,\"total\":2}}");
        GoVectorReplica replica = new GoVectorReplica(fake);

        int inserted = replica.replace("d1", Arrays.asList(
                entry("d1", 0, "go-hash-degraded", 1f, 0f),
                entry("d1", 1, "go-hash-degraded", 0f, 1f)));

        assertEquals("vector.insert", fake.lastMethod);
        assertEquals("d1", fake.lastParams.get("document_id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) fake.lastParams.get("entries");
        assertEquals(2, entries.size());
        // 契约键名对齐 Go 端 vector.Entry 的 json tag（chunk_idx/vector/source_type/model_key）
        assertEquals(0, entries.get(0).get("chunk_idx"));
        assertEquals("text", entries.get(0).get("source_type"));
        assertEquals("go-hash-degraded", entries.get(0).get("model_key"));
        @SuppressWarnings("unchecked")
        List<Double> vec = (List<Double>) entries.get(0).get("vector");
        assertEquals(2, vec.size());
        assertEquals(1.0, vec.get(0), 1e-9);
        assertEquals(2, inserted);
    }

    @Test
    void remove解析_removed键() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":2,\"result\":{\"removed\":3,\"total\":0}}");
        GoVectorReplica replica = new GoVectorReplica(fake);

        assertEquals(3, replica.remove("d1"));
        assertEquals("vector.delete", fake.lastMethod);
        assertEquals("d1", fake.lastParams.get("document_id"));
    }

    @Test
    void search组装契约_query键与过滤透传() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":3,\"result\":{\"hits\":[{\"document_id\":\"d1\",\"chunk_index\":0,\"score\":0.873}],\"count\":1}}");
        GoVectorReplica replica = new GoVectorReplica(fake);

        List<GoProtocol.VectorSearchResult.HitItem> hits =
                replica.search(new float[]{0.1f, 0.2f}, 5, "text", "go-hash-degraded");

        assertEquals("vector.search", fake.lastMethod);
        // 契约修正点：Go 端参数键是 query（曾误发 vector——跨语言契约必须钉死键名）
        @SuppressWarnings("unchecked")
        List<Double> query = (List<Double>) fake.lastParams.get("query");
        assertEquals(0.1, query.get(0), 1e-6, "float→wire 双精度转换容差（0.1f = 0.1000000014…）");
        assertEquals(5, fake.lastParams.get("top_k"));
        assertEquals("text", fake.lastParams.get("source_type"));
        assertEquals("go-hash-degraded", fake.lastParams.get("model_key"));
        assertEquals(1, hits.size());
        assertEquals("d1", hits.get(0).document_id);
        assertEquals(0.873, hits.get(0).score, 1e-9);
    }

    @Test
    void search空过滤_键缺席而非空串() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":4,\"result\":{\"hits\":[],\"count\":0}}");
        GoVectorReplica replica = new GoVectorReplica(fake);

        List<GoProtocol.VectorSearchResult.HitItem> hits = replica.search(
                new float[]{1f}, 5, null, null);

        assertEquals("vector.search", fake.lastMethod);
        assertTrue(!fake.lastParams.containsKey("source_type"), "null 过滤 = 键缺席（Go 端零值同义）");
        assertTrue(!fake.lastParams.containsKey("model_key"));
        assertTrue(hits.isEmpty());
    }

    @Test
    void similarity解析_score键() {
        FakeGoChannel fake = new FakeGoChannel();
        fake.enqueue("{\"id\":5,\"result\":{\"score\":0.5}}");
        GoVectorReplica replica = new GoVectorReplica(fake);

        assertEquals(0.5, replica.similarity(new float[]{1f, 0f}, new float[]{1f, 1f}), 1e-9);
        assertEquals("vector.similarity", fake.lastMethod);
        assertEquals(2, ((List<?>) fake.lastParams.get("vector_a")).size());
    }
}
