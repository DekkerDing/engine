package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Go 工具箱适配器 —— 将 Go 引擎能力适配到领域端口。
 *
 * <p><b>设计意图（与 Python 的 ChannelEmbeddingProvider 对比）</b>
 * ChannelEmbeddingProvider 封装了"模型编码"这一个功能线（Python 模型→向量）；
 * GoToolboxProvider 封装 Go 引擎的<b>全部能力</b>——文本分块、分词、关键词、
 * 哈希降级向量、向量索引等。
 *
 * <p>当前 Go 引擎直接映射为：
 * <ul>
 *   <li><b>EmbeddingProvider（降级模式）</b>：hashing.generate——模型不可用时
 *       用确定性哈希生成伪向量，保证检索链路不中断</li>
 *   <li><b>文本工具</b>：text.chunk / text.tokenize / text.keywords——
 *       Java 端可直接注入此 Bean 替代 Python 的分词/关键词调用</li>
 *   <li><b>向量索引</b>：vector.*——内存向量索引（当前独立存储，不与 SQLite 数据库共享）</li>
 * </ul>
 *
 * <p><b>配置启用</b>：
 * <pre>
 * engine.go.enabled=true
 * engine.go.provider=text
 * </pre>
 */
@Component
@Profile("go-toolbox")
public class GoToolboxProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(GoToolboxProvider.class);

    private final GoChannel channel;

    public GoToolboxProvider(GoChannel channel) {
        this.channel = channel;
    }

    // ========== EmbeddingProvider 降级实现 ==========

    @Override
    public EmbeddingModality modality() {
        return EmbeddingModality.TEXT;
    }

    @Override
    public String modelKey() {
        return "go-hash-degraded";
    }

    @Override
    public int dimension() {
        return 512;
    }

    @Override
    public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
        List<Embedding> results = new ArrayList<>(resources.size());
        for (VectorableResource r : resources) {
            String text = r.vectorizableText();
            GoProtocol.HashResult hash = generateHash(text, dimension());

            double[] vec = new double[hash.vector.size()];
            for (int i = 0; i < hash.vector.size(); i++) {
                vec[i] = hash.vector.get(i);
            }
            results.add(new Embedding(vec, modelKey(), modality(), true));
        }
        return results;
    }

    // ========== text.* ==========

    /** 中文分词（search 模式生成 bigram） */
    public List<String> tokenize(String text, String mode) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("mode", mode != null ? mode : "search");
        GoProtocol.Response resp = channel.send("text.tokenize", params);
        GoProtocol.TokenizeResult r = GoProtocol.extractResult(resp, GoProtocol.TokenizeResult.class);
        return r != null ? r.tokens : new ArrayList<>();
    }

    /** 关键词提取 */
    public List<GoProtocol.KeywordsResult.KeywordItem> keywords(String text, int topK) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("top_k", topK);
        GoProtocol.Response resp = channel.send("text.keywords", params);
        GoProtocol.KeywordsResult r = GoProtocol.extractResult(resp, GoProtocol.KeywordsResult.class);
        return r != null ? r.keywords : new ArrayList<>();
    }

    /** 文本分块（句子对齐 + 滑动窗口） */
    public List<GoProtocol.ChunkResult.ChunkItem> chunkText(String documentId, String text) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        params.put("text", text);
        GoProtocol.Response resp = channel.send("text.chunk", params);
        GoProtocol.ChunkResult r = GoProtocol.extractResult(resp, GoProtocol.ChunkResult.class);
        return r != null ? r.chunks : new ArrayList<>();
    }

    // ========== vector.* ==========

    /** 向量检索 */
    public List<GoProtocol.VectorSearchResult.HitItem> vectorSearch(
            double[] queryVec, int topK, String sourceType, String modelKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("vector", toDoubleList(queryVec));
        params.put("top_k", topK);
        if (sourceType != null) params.put("source_type", sourceType);
        if (modelKey != null) params.put("model_key", modelKey);
        GoProtocol.Response resp = channel.send("vector.search", params);
        GoProtocol.VectorSearchResult r = GoProtocol.extractResult(resp, GoProtocol.VectorSearchResult.class);
        return r != null ? r.hits : new ArrayList<>();
    }

    /** 向量插入 */
    public int vectorInsert(String documentId, List<Map<String, Object>> entries) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        params.put("entries", entries);
        GoProtocol.Response resp = channel.send("vector.insert", params);
        Object inserted = resp.result != null ? resp.result.get("inserted") : 0;
        return inserted instanceof Number ? ((Number) inserted).intValue() : 0;
    }

    /** 向量删除 */
    public int vectorDelete(String documentId) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        GoProtocol.Response resp = channel.send("vector.delete", params);
        Object deleted = resp.result != null ? resp.result.get("deleted") : 0;
        return deleted instanceof Number ? ((Number) deleted).intValue() : 0;
    }

    /** 余弦相似度 */
    public double vectorSimilarity(double[] a, double[] b) {
        Map<String, Object> params = new HashMap<>();
        params.put("vector_a", toDoubleList(a));
        params.put("vector_b", toDoubleList(b));
        GoProtocol.Response resp = channel.send("vector.similarity", params);
        GoProtocol.SimilarityResult r = GoProtocol.extractResult(resp, GoProtocol.SimilarityResult.class);
        return r != null ? r.similarity : 0.0;
    }

    // ========== hashing.* ==========

    /** 哈希降级向量：模型不可用时生成确定性伪向量 */
    public GoProtocol.HashResult generateHash(String text, int dim) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("dim", dim);
        params.put("normalize", true);
        GoProtocol.Response resp = channel.send("hashing.generate", params);
        return GoProtocol.extractResult(resp, GoProtocol.HashResult.class);
    }

    // ========== sys.* ==========

    /** 引擎健康检查 */
    public GoProtocol.StatsResult stats() {
        GoProtocol.Response resp = channel.send("sys.stats", new HashMap<>());
        return GoProtocol.extractResult(resp, GoProtocol.StatsResult.class);
    }

    // ========== 工具方法 ==========

    private static List<Double> toDoubleList(double[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (double v : arr) list.add(v);
        return list;
    }
}