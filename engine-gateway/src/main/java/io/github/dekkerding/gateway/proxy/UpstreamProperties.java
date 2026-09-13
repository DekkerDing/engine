package io.github.dekkerding.gateway.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 反向代理目标配置 —— application.yml 中 gateway.upstream.* 的类型安全绑定。
 *
 * <p>【教学注释 · 为什么用 @ConfigurationProperties 而不是散落的 @Value】
 * 三个配置项属于同一个概念（"上游是谁、怎么连"），聚成一个类后：
 *   1. 注入处只依赖一个对象，配置项增减不改调用方代码；
 *   2. IDE 可导航、可重构；@Value 字符串拼错只在运行时才炸。
 *
 * <p>【超时值的取舍】（见 application.yml 注释）
 * 连接 2 秒：目标是本机环回，连不上基本就是 server 没起，快速失败不拖浏览器；
 * 读 60 秒：向量化背后是模型推理（首次还含模型加载），短了会误杀慢请求。
 */
@Component
@ConfigurationProperties(prefix = "gateway.upstream")
public class UpstreamProperties {

    /** 上游业务服务地址（engine-server，同机部署走环回） */
    private String baseUrl = "http://127.0.0.1:8081";

    /** 连接超时（毫秒）：环回地址，2 秒连不上即判定上游不可达 */
    private long connectTimeoutMs = 2000;

    /** 读超时（毫秒）：给模型推理留足时间，SSE 流式响应期间有数据到达就会续期 */
    private long readTimeoutMs = 60000;

    /**
     * 请求体大小闸门（字节）：超过即由网关直接拒绝、不转发。
     * 默认 55MB 与 server 的 spring.servlet.multipart.max-request-size 对齐
     * （server 侧 max-file-size=50MB，multipart 边界/字段开销留 5MB 余量）。
     *
     * <p>【为什么网关要替 server 拦一道】超限请求若照常流式转发，server 在
     * multipart 解析期就会拒绝并<b>重置连接</b>——此时网关还握着几十 MB 没写完，
     * 写端收到 RST，连 server 已生成的 400 错误体都拿不到，最终只能对浏览器
     * 报一个含混的网关故障。入口预检让浏览器秒级拿到与 server 一致的明确
     * 拒绝原因，还省掉了整包无效传输。
     */
    private long maxRequestBodyBytes = 57671680L;   // 55MB

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }
}
