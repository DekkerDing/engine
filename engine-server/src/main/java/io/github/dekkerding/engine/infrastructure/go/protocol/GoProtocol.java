package io.github.dekkerding.engine.infrastructure.go.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Go 引擎协议 DTO —— 与 Go 端 internal/protocol/protocol.go 逐字段对齐。
 *
 * <p>协议格式（stdin/stdout JSON 行，一行一帧）：
 * <pre>
 *   请求帧（stdin）：  {"id":1,"method":"text.chunk","params":{...}}
 *   成功帧（stdout）： {"id":1,"result":{...}}
 *   错误帧（stdout）： {"id":1,"error":{"code":1001,"message":"..."}}
 * </pre>
 *
 * <p>与 Python 协议的核心区别：
 * <ul>
 *   <li>Python 协议每个 op 有独立 payload 结构（EmbedBatch / Tokenize /
 *       Keywords 等），请求帧也按 op 定制</li>
 *   <li>Go 协议统一：所有方法共用 Request/Response 帧，params 透传 JSON，
 *       由各工具方法自行解析——新增方法零协议改动</li>
 * </ul>
 *
 * <p>【教学注释 · 为什么 Go 协议比 Python 协议更"统一"】
 *   Python 端是用 Py4J 远程调用 Python 对象的方法——天然是"方法=Java 接口方法"。
 *   Go 端是 JSON 行协议——方法只是一个字符串（"text.chunk"），参数只是 JSON 字节。
 *   这种"极薄协议"让 Go 和 Java 之间只有一层 JSON 帧的约定，
 *   新加一个 Go 工具方法不需要改协议（不像 Python 要加接口方法）。
 */
public final class GoProtocol {

    private GoProtocol() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ==================== 请求帧 ====================

    /** Java→Go 请求帧（与 Go 端 protocol.Request 对齐） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Request {
        public long id;
        public String method;
        /** params 透传——各方法自行解析，协议层不关心结构 */
        public Map<String, Object> params;
    }

    // ==================== 响应帧 ====================

    /** Go→Java 响应帧（与 Go 端 protocol.Response 对齐） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        public long id;
        /** 成功时非 null，失败时 null */
        public Map<String, Object> result;
        /** 失败时非 null，成功时 null */
        public ErrorObject error;

        public boolean isError() {
            return error != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorObject {
        public int code;
        public String message;
    }

    // ==================== 各工具方法的响应结构（从 result Map 中提取） ====================

    /** text.chunk 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChunkResult {
        public List<ChunkItem> chunks = Collections.emptyList();
        public int count;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ChunkItem {
            public String document_id;
            public int index;
            public String text;
        }
    }

    /** text.tokenize 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenizeResult {
        public List<String> tokens = Collections.emptyList();
        public int count;
        public String mode;
    }

    /** text.keywords 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeywordsResult {
        public List<KeywordItem> keywords = Collections.emptyList();

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class KeywordItem {
            public String term;
            public double weight;
        }
    }

    /** vector.search 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VectorSearchResult {
        public List<HitItem> hits = Collections.emptyList();
        public long took;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class HitItem {
            public String document_id;
            public int chunk_index;
            public double score;
            public String source_type;
            public String model_key;
        }
    }

    /** vector.similarity 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimilarityResult {
        public double similarity;
        public int dim_a;
        public int dim_b;
    }

    /** hashing.generate 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HashResult {
        public List<Double> vector = Collections.emptyList();
        public int dim;
        public boolean degraded;
        public String text;
    }

    /** sys.stats 的 result 载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatsResult {
        public String engine;
        public String version;
        public int vector_count;
        public List<String> tools = Collections.emptyList();
    }

    // ==================== 解析工厂 ====================

    /** 从 Response.result Map 中提取指定类型的载荷 */
    public static <T> T extractResult(Response response, Class<T> type) {
        if (response.result == null) {
            return null;
        }
        return MAPPER.convertValue(response.result, type);
    }

    public static Response parseResponse(String json) {
        try {
            return MAPPER.readValue(json, Response.class);
        } catch (Exception e) {
            throw new IllegalStateException("Go 引擎响应反序列化失败: " + truncate(json), e);
        }
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Go 协议序列化失败", e);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}