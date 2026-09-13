package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import io.github.dekkerding.engine.infrastructure.search.InMemoryVectorIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go 轨路由测试 —— 任务 4.3 的验收点：InMemoryVectorIndex 检索经
 * GoVectorReplica 的路径行为 + 空间闸门语义对齐（design D5：闸门判定
 * 在 Java 侧完成，文案与关闭态单源同款——本测试直接断言这一点）。
 *
 * <p>【测试矩阵】
 * <pre>
 * 场景                     Go 轨返回        预期
 * 同模态模型切换（stale）   空 hits          400 + 修复指引（本地轨裁决——闸门文案单源）
 * 正常命中                 三元组 hits      回表组装 VectorHit（score 取 Go，条目取主索引）
 * 通道异常（崩溃/超时）     EngineException  降级本地扫描，结果与关闭态一致
 * 回表 miss（同步断裂）     未知三元组       降级本地扫描
 * 副本未同步（空库）        空 hits          空结果（主索引也空——合法真空）
 * </pre>
 */
class GoVectorIndexRoutingTest {

    /** 可控假通道：可回放帧、可注入异常。 */
    static class ScriptedChannel implements GoChannel {
        final Deque<Object> frames = new ArrayDeque<>(); // String=JSON 帧；Throwable=异常注入
        String lastMethod;

        void enqueue(String json) {
            frames.addLast(json);
        }

        void failWith(Throwable t) {
            frames.addLast(t);
        }

        @Override
        public String channelName() {
            return "scripted";
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
        @SuppressWarnings("unchecked")
        public GoProtocol.Response send(String method, Map<String, Object> params) {
            lastMethod = method;
            Object frame = frames.pollFirst();
            if (frame instanceof Throwable) {
                throw EngineException.downstream(((Throwable) frame).getMessage());
            }
            if (frame == null) {
                throw new IllegalStateException("脚本帧耗尽，未预期的调用: " + method);
            }
            return GoProtocol.parseResponse((String) frame);
        }
    }

    private static VectorEntry textEntry(String docId, int chunkIdx, String modelKey,
                                         String text, float... vector) {
        return new VectorEntry(docId, "text", chunkIdx, text, modelKey, false, vector, Instant.now());
    }

    // ---------- 闸门语义对齐（任务 4.3 验收主项） ----------

    /** replace 的复制确认帧（4.4 起每次 replace 都会先发 vector.insert——脚本须备帧）。 */
    private static String insertAck(int n) {
        return "{\"id\":0,\"result\":{\"inserted\":" + n + ",\"total\":" + n + "}}";
    }

    @Test
    void 同模态模型切换_Go轨空结果_经本地轨闸门抛400与指引() {
        ScriptedChannel fake = new ScriptedChannel();
        fake.enqueue(insertAck(1));
        // Go 副本视角：按新模型 go-hash-degraded 过滤 → 空命中（副本里没有该空间的条目）
        fake.enqueue("{\"id\":1,\"result\":{\"hits\":[],\"count\":0}}");
        InMemoryVectorIndex routed = new InMemoryVectorIndex(new GoVectorReplica(fake));
        // 主索引：旧模型 text-embedding-zh 的 2 维向量（模拟用户曾用旧模型摄取）
        routed.replace("d1", Arrays.asList(
                textEntry("d1", 0, "text-embedding-zh", "旧块", 1f, 0f)));

        EngineException e = assertThrows(EngineException.class,
                () -> routed.search(new float[]{1f, 0f}, 10, "text", "go-hash-degraded"));

        // 空间闸门语义对齐（design D5：文案与关闭态 spaceGateError 单源同款）
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("不可比"), "闸门文案应说明不可比: " + e.getMessage());
        assertTrue(e.getMessage().contains("重新摄取") || e.getMessage().contains("切换回原模型"),
                "闸门文案应含修复指引: " + e.getMessage());
        assertTrue(e.getMessage().contains("text-embedding-zh"),
                "库内空间清单应列出 stale 模型: " + e.getMessage());
    }

    @Test
    void Go轨命中_回表组装完整条目() {
        ScriptedChannel fake = new ScriptedChannel();
        fake.enqueue(insertAck(2)); // replace 的复制帧（先于检索被消耗）
        fake.enqueue("{\"id\":1,\"result\":{\"hits\":["
                + "{\"document_id\":\"d1\",\"chunk_index\":1,\"score\":0.9},"
                + "{\"document_id\":\"d1\",\"chunk_index\":0,\"score\":0.5}"
                + "],\"count\":2}}");
        InMemoryVectorIndex index = new InMemoryVectorIndex(new GoVectorReplica(fake));
        index.replace("d1", Arrays.asList(
                textEntry("d1", 0, "go-hash-degraded", "第一块", 1f, 0f),
                textEntry("d1", 1, "go-hash-degraded", "第二块", 0f, 1f)));

        List<VectorHit> hits = index.search(new float[]{0f, 1f}, 5, "text", "go-hash-degraded");

        // score 取 Go 轨（含定序），条目（文本/模型键/降级标志）回自主索引
        assertEquals(2, hits.size());
        assertEquals("第二块", hits.get(0).getEntry().getText());
        assertEquals(0.9, hits.get(0).getScore(), 1e-9);
        assertEquals("go-hash-degraded", hits.get(0).getEntry().getModelKey());
        assertEquals("第一块", hits.get(1).getEntry().getText());
        assertEquals(0.5, hits.get(1).getScore(), 1e-9);
    }

    @Test
    void 通道异常_降级本地扫描_结果与关闭态一致() {
        // 关闭态基线：无副本直接本地扫
        InMemoryVectorIndex localOnly = new InMemoryVectorIndex();
        localOnly.replace("d1", Arrays.asList(
                textEntry("d1", 0, "go-hash-degraded", "唯一块", 1f, 0f)));
        List<VectorHit> baseline = localOnly.search(new float[]{1f, 0f}, 5, "text", "go-hash-degraded");

        ScriptedChannel fake = new ScriptedChannel();
        fake.enqueue(insertAck(1)); // replace 的复制先成功
        fake.failWith(new RuntimeException("模拟引擎崩溃：进程已退出")); // 检索时崩溃
        InMemoryVectorIndex routed = new InMemoryVectorIndex(new GoVectorReplica(fake));
        routed.replace("d1", Arrays.asList(
                textEntry("d1", 0, "go-hash-degraded", "唯一块", 1f, 0f)));

        // Go 轨炸 → 本地轨兜底：命中集合与分数与关闭态完全一致（可用性优先于加速）
        List<VectorHit> hits = routed.search(new float[]{1f, 0f}, 5, "text", "go-hash-degraded");
        assertEquals(baseline.size(), hits.size());
        assertEquals(baseline.get(0).getEntry().getDocumentId(), hits.get(0).getEntry().getDocumentId());
        assertEquals(baseline.get(0).getScore(), hits.get(0).getScore(), 1e-9);
    }

    @Test
    void 回表miss_降级本地扫描() {
        ScriptedChannel fake = new ScriptedChannel();
        fake.enqueue(insertAck(1)); // replace 的复制帧
        // 副本命中一条主索引没有的条目（同步断裂的理论病态）→ 兜底
        fake.enqueue("{\"id\":1,\"result\":{\"hits\":["
                + "{\"document_id\":\"ghost\",\"chunk_index\":0,\"score\":0.99}"
                + "],\"count\":1}}");
        InMemoryVectorIndex index = new InMemoryVectorIndex(new GoVectorReplica(fake));
        index.replace("d1", Arrays.asList(
                textEntry("d1", 0, "go-hash-degraded", "真实块", 1f, 0f)));

        List<VectorHit> hits = index.search(new float[]{1f, 0f}, 5, "text", "go-hash-degraded");

        assertEquals(1, hits.size(), "幽灵命中被丢弃，本地扫描给出真实结果");
        assertEquals("d1", hits.get(0).getEntry().getDocumentId());
    }

    @Test
    void 副本未同步但主索引同样空_合法空结果不抛闸门() {
        ScriptedChannel fake = new ScriptedChannel();
        fake.enqueue("{\"id\":1,\"result\":{\"hits\":[],\"count\":0}}");
        InMemoryVectorIndex index = new InMemoryVectorIndex(new GoVectorReplica(fake));
        // 主索引也空（启动即查询——4.4 灌入之前的中间态）→ 空结果，闸门不触发

        List<VectorHit> hits = index.search(new float[]{1f, 0f}, 5, "text", "go-hash-degraded");

        assertTrue(hits.isEmpty(), "真空库 = 合法空结果（目标模态无条目不构成闸门错误）");
    }
}
