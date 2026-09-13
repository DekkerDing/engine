package io.github.dekkerding.engine.interfaces.rest;

import java.time.Instant;

/**
 * 统一响应体 —— 所有 REST 接口的唯一返回格式。
 *
 * <p>【为什么需要统一响应体】（前端工程化的地基）
 * 前端每次请求都要判断"成功还是失败"。如果每个接口各返回各的格式，
 * 前端就得为每个接口写一套解析逻辑。统一成一种信封（envelope）后，
 * 前端只写一个 http 拦截器就能处理所有接口的成功/失败/降级。
 *
 * <p>【字段契约】（与 frontend/src/api/types.ts 一一映射，改动需双向同步）
 * <pre>
 * {
 *   "code": 0,                        // 业务码：0 成功；错误按段位分区（见下）
 *   "message": "ok",                  // 人类可读信息：成功时 "ok"，失败时给用户看的中文提示
 *   "data": { ... },                  // 业务数据：成功才有值，失败为 null
 *   "timestamp": "2026-08-30T09:00:00Z",
 *   "traceId": "a1b2c3d4"             // 链路追踪 ID：一个请求贯穿 网关→server→Python，排查问题用
 * }
 * </pre>
 *
 * <p>【教学注释 · 业务码分段】（design.md D9）
 * HTTP 状态码表达"传输层怎么了"（400/404/500/503），业务码表达"谁的责任"：
 * <pre>
 *   0      成功
 *   1xxx   参数错误（前端传错：缺字段、超限、格式不支持）
 *   2xxx   领域错误（业务规则拒绝：资源不存在、状态机非法流转）
 *   3xxx   Python 下游错误（引擎不可达、超时、降级）
 *   5xxx   系统错误（未预期异常，我方 bug）
 * </pre>
 * 前端按段位决定交互（1xxx 提示改输入、3xxx 提示稍后重试、5xxx 显示兜底页），
 * 比解析 message 字符串可靠得多——message 是给人看的，code 才是给程序看的。
 *
 * <p>【教学注释 · 泛型】{@code ApiResponse<T>} 的 T 表示 data 的类型：
 * {@code ApiResponse<List<DocumentSummary>>} 让编译器帮我们检查返回类型，而不是到处 Object。
 *
 * <p>【教学注释 · 不可变】所有字段 final、只提供 getter —— 值对象（Value Object）模式：
 * 创建后不可修改，天然线程安全，避免响应对象在传递中被意外篡改。
 */
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String timestamp;
    private final String traceId;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toString();
        this.traceId = traceId;
    }

    /** 成功响应：code=0，data 为业务数据 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, currentTraceId());
    }

    /** 失败响应：code 语义化（400/404/500/503...），message 面向用户 */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, currentTraceId());
    }

    /**
     * 取当前链路追踪 ID。
     * 【教学注释】真实项目常用 SLF4J MDC（Mapped Diagnostic Context）把 traceId
     * 放进日志上下文；这里简化为空实现，P7 文档里留了扩展说明。
     */
    private static String currentTraceId() {
        return null;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public String getTimestamp() { return timestamp; }
    public String getTraceId() { return traceId; }
}
