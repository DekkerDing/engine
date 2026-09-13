package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引副本客户端 —— JVM 主索引到 Go 向量引擎的复制与检索通道（场景二 · design D4）。
 *
 * <p>【架构位置】打分向量在 JVM 堆里，Go 并行扫描需要数据——采纳"副本同步"：
 * 摄取时把条目复制进 Go 进程，检索时发查询收 (doc_id, chunk_index, score) 三元组。
 * 本类是这条复制链的 Java 端点：只做协议翻译，不做业务判断——
 * 空间闸门/回表/降级兜底都在 {@code InMemoryVectorIndex}（判定数据在那里）。
 *
 * <p>【装配门控（D7 第一级）】仅 {@code engine.go.enabled=true} 装配——
 * 与是否激活 go-toolbox Profile 无关：场景二（检索加速）与场景三（降级向量化）
 * 是两个独立场景，检索副本只认第一级开关。默认 false 时本类零装配零进程。
 *
 * <p>【一致性边界（写进 spec）】复制命令先于摄取事务返回发出；检索经通道
 * call() 互斥锁串行化——复制与查询在 Go 进程内天然线性一致。
 */
@Component
@ConditionalOnProperty(name = "engine.go.enabled", havingValue = "true")
public class GoVectorReplica {

    private static final Logger log = LoggerFactory.getLogger(GoVectorReplica.class);

    private final GoChannel channel;

    public GoVectorReplica(GoChannel channel) {
        this.channel = channel;
    }

    /**
     * 复制一个文档的全部条目进 Go 副本（幂等替换——与主索引 replace 同语义）。
     *
     * @return Go 侧确认的条目数（对拍/自检用；调用方通常忽略）
     */
    public int replace(String documentId, List<VectorEntry> entries) {
        List<Map<String, Object>> items = new ArrayList<>(entries.size());
        for (VectorEntry entry : entries) {
            // 契约对齐 Go 端 vector.Entry 的 json tag：chunk_idx/vector/source_type/model_key
            // （doc_id 顶层统一携带，条目内不重复——Go 端会兜底继承）
            Map<String, Object> item = new HashMap<>();
            item.put("chunk_idx", entry.getChunkIndex());
            item.put("vector", toDoubleList(entry.getVector()));
            item.put("source_type", entry.getSourceType());
            item.put("model_key", entry.getModelKey());
            items.add(item);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        params.put("entries", items);
        GoProtocol.Response resp = channel.send("vector.insert", params);
        Object inserted = resp.result != null ? resp.result.get("inserted") : 0;
        return inserted instanceof Number ? ((Number) inserted).intValue() : 0;
    }

    /** 从 Go 副本删除一个文档的全部条目（幂等——删不存在的返回 0）。 */
    public int remove(String documentId) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        GoProtocol.Response resp = channel.send("vector.delete", params);
        Object removed = resp.result != null ? resp.result.get("removed") : 0;
        return removed instanceof Number ? ((Number) removed).intValue() : 0;
    }

    /**
     * 并行 Top-K 余弦检索（Go 侧分片扫描）。
     *
     * <p>命中只含 (document_id, chunk_index, score) 三元组——Go 是计算副本不回表，
     * 完整条目（文本/降级标志）由 JVM 主索引持有，调用方按三元组回表。
     *
     * @param sourceType null = 不过滤模态（Go 端空串同义）
     * @param modelKey   null = 不过滤模型
     */
    public List<GoProtocol.VectorSearchResult.HitItem> search(
            float[] queryVector, int topK, String sourceType, String modelKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", toDoubleList(queryVector));
        params.put("top_k", topK);
        if (sourceType != null) {
            params.put("source_type", sourceType);
        }
        if (modelKey != null) {
            params.put("model_key", modelKey);
        }
        GoProtocol.Response resp = channel.send("vector.search", params);
        GoProtocol.VectorSearchResult r = GoProtocol.extractResult(resp, GoProtocol.VectorSearchResult.class);
        return r != null ? r.hits : new ArrayList<>();
    }

    /** 两向量余弦（对拍自检用）：Go 响应键为 score（与 vector.search 命中同名字段）。 */
    public double similarity(float[] a, float[] b) {
        Map<String, Object> params = new HashMap<>();
        params.put("vector_a", toDoubleList(a));
        params.put("vector_b", toDoubleList(b));
        GoProtocol.Response resp = channel.send("vector.similarity", params);
        GoProtocol.SimilarityResult r = GoProtocol.extractResult(resp, GoProtocol.SimilarityResult.class);
        return r != null ? r.score : 0.0;
    }

    private static List<Double> toDoubleList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add((double) v);
        }
        return list;
    }
}
