package io.github.dekkerding.engine.infrastructure.python.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Python 协议 DTO —— 跨语言边界的强类型契约。
 *
 * <p>【修复蓝本缺陷 b】蓝本 Python 端吞异常返回 {'error': ...} 字典，
 * Java 端期望 double[] 却拿到 dict → ClassCastException 运行时才爆。
 * 现在边界只传 JSON 字符串，两端各自反序列化成编译期强类型，
 * 协议漂移在第一次调用就会暴露（fail fast）。
 *
 * <p>【教学注释 · @JsonIgnoreProperties(ignoreUnknown=true)】
 * Python 端以后给响应加字段（比如新模型信息），Java 旧版本反序列化也不会炸——
 * 跨语言协议的兼容性常识：未知字段忽略而不是报错。
 */
public final class PythonProtocol {

    /** 工具类禁止实例化 */
    private PythonProtocol() {
    }

    /** 全局共享的 mapper：ObjectMapper 创建昂贵且线程安全，静态单例即可 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 批量编码结果（embed op 的 payload） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbedBatch {
        public List<List<Double>> vectors;
        public int dim;
        public String model;
        public boolean degraded;

        /** Jackson 给的是 Double 包装列表；数值计算要原始 float[]（性能见 Embedding 注释） */
        public float[][] toFloatArrays() {
            float[][] result = new float[vectors.size()][];
            for (int i = 0; i < vectors.size(); i++) {
                List<Double> row = vectors.get(i);
                float[] arr = new float[row.size()];
                for (int j = 0; j < row.size(); j++) {
                    arr[j] = row.get(j).floatValue();
                }
                result[i] = arr;
            }
            return result;
        }
    }

    /** 分词结果 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tokenize {
        public List<String> tokens = Collections.emptyList();
    }

    /** 关键词条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeywordItem {
        public String term;
        public double weight;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Keywords {
        public List<KeywordItem> keywords = Collections.emptyList();
    }

    /** 引擎状态快照（stats op） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stats {
        public Map<String, Object> registry = Collections.emptyMap();
        public EmbeddingStatus embedding;
        /** CLIP 跨模态引擎状态（结构同文本引擎，前端可同构渲染） */
        public EmbeddingStatus vision;
        /** 语义拆分标注器状态（本期 mock：mocked=true 必须如实透出） */
        public AnnotationStatus annotation;
        /** 重排器状态（available=null 表示尚未尝试加载——health 探测不触发加载） */
        public RerankerStatus reranker;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class EmbeddingStatus {
            public String model_key;
            public String model_name;
            public int dimension;
            public boolean real_model_loaded;
            public String load_error;
            public boolean degraded;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AnnotationStatus {
            public String model_key;
            public String implementation;
            public boolean mocked;
            public boolean deterministic;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class RerankerStatus {
            public String model_key;
            public String model_name;
            public int max_candidates;
            public boolean real_model_loaded;
            public String load_error;
            /** null=未尝试加载；true/false=已尝试后的可用性 */
            public Boolean available;
        }
    }

    /** 图片结构化标注（annotate op 的 payload：mock 与未来真实 VLM 的共同契约） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Annotation {
        public String subject;
        public String description;
        public List<String> tags = Collections.emptyList();
        public boolean mocked;
    }

    /** 重排结果（rerank op 的 payload：分数与候选顺序一一对应） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RerankScores {
        public List<Double> scores = Collections.emptyList();

        public float[] toFloatArray() {
            float[] result = new float[scores.size()];
            for (int i = 0; i < scores.size(); i++) {
                result[i] = scores.get(i).floatValue();
            }
            return result;
        }
    }

    /** stdio 错误信封（ok:false 时的 error 字段） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorEnvelope {
        public String type;
        public String message;
        public boolean retryable;
    }

    // ---------- 解析工厂：JSON 字符串 → 强类型 ----------

    public static EmbedBatch parseEmbedBatch(String json) {
        return parse(json, EmbedBatch.class);
    }

    public static Tokenize parseTokenize(String json) {
        return parse(json, Tokenize.class);
    }

    public static Keywords parseKeywords(String json) {
        return parse(json, Keywords.class);
    }

    public static Stats parseStats(String json) {
        return parse(json, Stats.class);
    }

    public static Annotation parseAnnotation(String json) {
        return parse(json, Annotation.class);
    }

    public static RerankScores parseRerankScores(String json) {
        return parse(json, RerankScores.class);
    }

    public static ErrorEnvelope parseError(String json) {
        return parse(json, ErrorEnvelope.class);
    }

    private static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Python 响应反序列化失败: " + truncate(json), e);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
