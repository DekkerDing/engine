package io.github.dekkerding.engine.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 需求域 JSON 编解码小工具 —— form_data_json / files_json 列的序列化。
 *
 * <p>【边界纪律】Jackson 只出现在 infrastructure（domain 保持纯 POJO）；
 * IO 失败包装为 UncheckedIOException（Spring 事务边界统一兜底）。
 */
final class RequirementJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 有序文件映射的类型引用（files_json / template_output_json / revised_json 共用） */
    static final TypeReference<LinkedHashMap<String, String>> FILES_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() { };

    private RequirementJson() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON 序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }

    static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON 反序列化失败: " + type.getSimpleName(), e);
        }
    }

    static LinkedHashMap<String, String> readFiles(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<String, String>();
        }
        try {
            return MAPPER.readValue(json, FILES_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON 反序列化失败: files map", e);
        }
    }
}
