package io.github.dekkerding.gateway.common;

import java.time.Instant;

/**
 * 网关统一响应体 —— 与 engine-server 的 ApiResponse 同构（字段一一对应）。
 *
 * <p>【教学注释 · 为什么"复制"而不是"抽公共模块"】
 * server 与 gateway 是两个独立部署的 Spring Boot 应用，唯一的耦合是 HTTP 协议
 * （见 GatewayApplication 注释）。若为这一个类建共享 module，两应用就产生了
 * 代码级依赖——"信封格式一致"是协议约定（接口契约），不是代码复用问题。
 * 真实项目里这两个类通常由 API 契约（OpenAPI）生成，原理相同：契约同步，代码各写各的。
 *
 * <p>字段契约与分段规则见 server 侧同名类注释（此处只列出网关用到的部分）：
 * 网关自身错误用 5xxx 系统段（5001 = 上游不可达，见 ProxyController）。
 */
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String timestamp;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toString();
    }

    /** 成功响应：code=0，data 为业务数据 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /** 失败响应：code 语义化分段，message 面向用户 */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public String getTimestamp() { return timestamp; }
}
