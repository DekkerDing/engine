package io.github.dekkerding.engine.infrastructure.go.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Go 引擎协议 DTO —— 与 Go 端 internal/protocol/protocol.go 逐字段对齐。
 */
public final class GoProtocol {

    private GoProtocol() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static class Request {
        public long id;
        public String method;
        public Map<String, Object> params;
    }

    public static class Response {
        public long id;
        public Map<String, Object> result;
        public ErrorObject error;

        public boolean isError() {
            return error != null;
        }
    }

    public static class ErrorObject {
        public int code;
        public String message;
    }

    /** text.chunk */
    public static class ChunkResult {
        public List<ChunkItem> chunks = Collections.emptyList();
        public int count;

        public static class ChunkItem {
            public String document_id;
            public int index;
            public String text;
        }
    }

    /** text.tokenize */
    public static class TokenizeResult {
        public List<String> tokens = Collections.emptyList();
        public int count;
        public String mode;
    }

    /** text.keywords */
    public static class KeywordsResult {
        public List<KeywordItem> keywords = Collections.emptyList();

        public static class KeywordItem {
            public String term;
            public double weight;
        }
    }

    /** vector.search（命中三元组：完整条目由 JVM 主索引回表——Go 是计算副本不存元数据） */
    public static class VectorSearchResult {
        public List<HitItem> hits = Collections.emptyList();
        public int count;

        public static class HitItem {
            public String document_id;
            public int chunk_index;
            public double score;
        }
    }

    /** vector.similarity（Go 响应键为 score——与 vector.search 命中同名字段） */
    public static class SimilarityResult {
        public double score;
    }

    /** hashing.generate */
    public static class HashResult {
        public List<Double> vector = Collections.emptyList();
        public int dim;
        public boolean degraded;
        public String text;
    }

    /** sys.stats */
    public static class StatsResult {
        public String engine;
        public String version;
        public int vector_count;
        public List<String> tools = Collections.emptyList();
    }

    // ==================== 工具方法 ====================

    public static <T> T extractResult(Response response, Class<T> type) {
        if (response.result == null) return null;
        return MAPPER.convertValue(response.result, type);
    }

    public static Response parseResponse(String json) {
        try {
            return MAPPER.readValue(json, Response.class);
        } catch (Exception e) {
            throw new IllegalStateException("Go engine response parse failed: " + truncate(json), e);
        }
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Go protocol serialize failed", e);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}