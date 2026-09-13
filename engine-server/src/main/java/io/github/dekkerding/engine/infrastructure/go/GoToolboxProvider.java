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

@Component
@Profile("go-toolbox")
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

    public int vectorInsert(String documentId, List<Map<String, Object>> entries) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        params.put("entries", entries);
        GoProtocol.Response resp = channel.send("vector.insert", params);
        Object inserted = resp.result != null ? resp.result.get("inserted") : 0;
        return inserted instanceof Number ? ((Number) inserted).intValue() : 0;
    }

    public int vectorDelete(String documentId) {
        Map<String, Object> params = new HashMap<>();
        params.put("document_id", documentId);
        GoProtocol.Response resp = channel.send("vector.delete", params);
        Object deleted = resp.result != null ? resp.result.get("deleted") : 0;
        return deleted instanceof Number ? ((Number) deleted).intValue() : 0;
    }

    public double vectorSimilarity(double[] a, double[] b) {
        Map<String, Object> params = new HashMap<>();
        params.put("vector_a", toDoubleList(a));
        params.put("vector_b", toDoubleList(b));
        GoProtocol.Response resp = channel.send("vector.similarity", params);
        GoProtocol.SimilarityResult r = GoProtocol.extractResult(resp, GoProtocol.SimilarityResult.class);
        return r != null ? r.similarity : 0.0;
    }

    // ========== hashing.* ==========

    public GoProtocol.HashResult generateHash(String text, int dim) {
        Map<String, Object> params = new HashMap<>();
        params.put("text", text);
        params.put("dim", dim);
        params.put("normalize", true);
        GoProtocol.Response resp = channel.send("hashing.generate", params);
        return GoProtocol.extractResult(resp, GoProtocol.HashResult.class);
    }

    // ========== sys.* ==========

    public GoProtocol.StatsResult stats() {
        GoProtocol.Response resp = channel.send("sys.stats", new HashMap<>());
        return GoProtocol.extractResult(resp, GoProtocol.StatsResult.class);
    }

    private static List<Double> toDoubleList(double[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (double v : arr) list.add(v);
        return list;
    }
}