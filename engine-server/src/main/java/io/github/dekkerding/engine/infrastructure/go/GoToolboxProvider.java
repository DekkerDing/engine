package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配两级门控（D7）：Profile("go-toolbox") 让 python provider 让位（避免同模态重复注册），
 * engine.go.enabled=true 保证通道在场——双条件缺一不可：只开 Profile 没通道会装配失败。
 */
@Component
@Profile("go-toolbox")
@ConditionalOnProperty(name = "engine.go.enabled", havingValue = "true")
public class GoToolboxProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(GoToolboxProvider.class);
    private final GoChannel channel;

    public GoToolboxProvider(GoChannel channel) {
        this.channel = channel;
    }

    // ========== EmbeddingProvider (degraded mode) ==========

    @Override
    public EmbeddingModality modality() { return EmbeddingModality.TEXT; }

    @Override
    public String modelKey() { return "go-hash-degraded"; }

    @Override
    public int dimension() { return 512; }

    @Override
    public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
        List<Embedding> results = new ArrayList<>(resources.size());
        for (VectorableResource r : resources) {
            String text = r.asText();
            GoProtocol.HashResult hash = generateHash(text, dimension());
            // Go 返回 double[], Embedding 要求 float[]（数值密集场景用原始类型）
            float[] vec = new float[hash.vector.size()];
            for (int i = 0; i < hash.vector.size(); i++) {
                vec[i] = hash.vector.get(i).floatValue();
            }
            results.add(new Embedding(vec, modelKey(), true));
        }
        return results;
    }

    // ========== text.* ==========

    public List<String> tokenize(String text, String mode) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("mode", mode != null ? mode : "search");
        GoProtocol.Response resp = channel.send("text.tokenize", params);
        GoProtocol.TokenizeResult r = GoProtocol.extractResult(resp, GoProtocol.TokenizeResult.class);
        return r != null ? r.tokens : new ArrayList<>();
    }

    public List<GoProtocol.KeywordsResult.KeywordItem> keywords(String text, int topK) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("top_k", topK);
        GoProtocol.Response resp = channel.send("text.keywords", params);
        GoProtocol.KeywordsResult r = GoProtocol.extractResult(resp, GoProtocol.KeywordsResult.class);
        return r != null ? r.keywords : new ArrayList<>();
    }

    public List<GoProtocol.ChunkResult.ChunkItem> chunkText(String documentId, String text) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        params.put("text", text);
        GoProtocol.Response resp = channel.send("text.chunk", params);
        GoProtocol.ChunkResult r = GoProtocol.extractResult(resp, GoProtocol.ChunkResult.class);
        return r != null ? r.chunks : new ArrayList<>();
    }

    // ========== vector.* ==========
    // 索引副本的复制与检索协议已迁至 GoVectorReplica（场景二只认第一级开关
    // engine.go.enabled，与本类的 go-toolbox Profile 双条件无关——职责分离：
    // 本类是 EmbeddingProvider 端口的降级实现 + text.* 门面）。

    // ========== hashing.* ==========

    /** 降级向量化主入口：L2 归一（点积即余弦，与检索打分语义对齐）。 */
    public GoProtocol.HashResult generateHash(String text, int dim) {
        return generateHash(text, dim, true);
    }

    /** 全参版：normalize=false 返回原始哈希幅度（确定性校验/教学对拍用）。 */
    public GoProtocol.HashResult generateHash(String text, int dim, boolean normalize) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("dim", dim);
        params.put("normalize", normalize);
        GoProtocol.Response resp = channel.send("hashing.generate", params);
        return GoProtocol.extractResult(resp, GoProtocol.HashResult.class);
    }

    // ========== sys.* ==========

    public GoProtocol.StatsResult stats() {
        GoProtocol.Response resp = channel.send("sys.stats", new HashMap<>());
        return GoProtocol.extractResult(resp, GoProtocol.StatsResult.class);
    }
}