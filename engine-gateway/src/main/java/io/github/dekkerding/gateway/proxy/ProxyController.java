package io.github.dekkerding.gateway.proxy;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 反向代理 —— 网关三件事之二：/api/** 剥前缀转发到 engine-server（127.0.0.1:8081）。
 *
 * <p>【反向代理是什么】浏览器只认识网关（:8090）；网关把 /api 开头的请求
 * "原样搬运"给上游业务服务，再把上游响应"原样搬运"回浏览器。
 * 浏览器全程无感知——这就是"唯一流量入口"的含义。
 *
 * <p>【教学注释 · 实现的三个关键决策】
 * <ol>
 *   <li><b>OkHttp 单例</b>：OkHttpClient 自带连接池与线程池，每个请求 new 一个
 *       会耗尽资源——整个应用共享一个实例（官方文档明确建议）。</li>
 *   <li><b>双向流式透传</b>：请求体用 Okio Source 包装 servlet 输入流、响应体
 *       直接把上游 byteStream 拷给 servlet 输出流（8KB 缓冲循环），全程不把
 *       整包读进内存——这样未来 SSE 分块推送、大文件上传都天然支持。</li>
 *   <li><b>hop-by-hop 头剔除</b>：Connection/Transfer-Encoding/Keep-Alive 这类
 *       头描述的是"这一跳连接"的语义，跨代理转发它们会造成语义错乱；
 *       Content-Length 也剔除（servlet 容器对写出内容自行计算，避免与实际
 *       字节数不一致的经典 bug）。</li>
 * </ol>
 *
 * <p>【URL 细节】request.getRequestURI() 返回的是<b>未解码</b>的原始 URI
 * （中文会保留 %XX 转义），直接拼接转发才能保证上游收到一致的编码——
 * 若先 URLDecode 再拼，"+" 会被错误还原成空格。
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** hop-by-hop 头（RFC 7230 第 6.1 节）：只属于单跳连接，代理必须剥掉 */
    private static final Set<String> HOP_BY_HOP_HEADERS = new HashSet<>(Arrays.asList(
            "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
            "TE", "Trailer", "Transfer-Encoding", "Upgrade"));

    /** 请求方向额外剔除：Host 由 OkHttp 按目标重写；Content-Length 随体重算 */
    private static final String REQ_CONTENT_LENGTH = "Content-Length";

    /** 响应方向额外剔除：长度由 servlet 容器对实际写出字节自行计算 */
    private static final String RESP_CONTENT_LENGTH = "Content-Length";

    private final OkHttpClient httpClient;
    private final String upstreamBase;
    private final long maxRequestBodyBytes;

    /** 客户端由 OkHttpConfig 统一装配（与健康探测共享连接池），超时策略见 UpstreamProperties 注释 */
    public ProxyController(UpstreamProperties properties, OkHttpClient httpClient) {
        this.upstreamBase = stripTrailingSlash(properties.getBaseUrl());
        this.maxRequestBodyBytes = properties.getMaxRequestBodyBytes();
        this.httpClient = httpClient;
    }

    /**
     * 通配接管所有 /api/** 请求（任意 HTTP 方法）。
     *
     * <p>【Spring 路由优先级】6.3 的聚合健康端点 /api/system/health 是精确匹配，
     * 会优先于本通配命中——网关自己的端点不会被代理"吃掉"。
     */
    @RequestMapping("/api/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 请求体大小闸门：超限由网关直接拒绝（原因与 server 侧一致），不做无效转发。
        // 见 UpstreamProperties#maxRequestBodyBytes 注释——转发一个注定被拒的大包，
        // 换来的是连接重置与含混的网关故障。
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxRequestBodyBytes) {
            writeEnvelopedError(response, 400, 1000, "文件超过大小限制（50MB）");
            return;
        }

        String target = buildTargetUrl(request);
        try {
            Request upstreamRequest = buildUpstreamRequest(request, target);
            relay(upstreamRequest, response);
        } catch (ConnectException e) {
            // 连接被拒：server 进程不在监听——秒级失败，绝不挂起浏览器（spec: 上游不可达的明确失败）
            writeGatewayError(response, 502, "上游服务不可用（engine-server 未启动或端口不通）");
        } catch (SocketTimeoutException e) {
            // 连接超时（环回 2 秒）：同样秒级失败
            writeGatewayError(response, 504, "连接上游服务超时");
        } catch (IOException e) {
            // 其余 IO 故障兜底：典型如上游提前断开导致请求体写出中断（Stream closed /
            // Connection reset）。不兜底就会漏成裸 500——spec 要求网关永不吞错、永不挂起。
            log.warn("代理转发中断: {} {}", target, e.toString());
            writeGatewayError(response, 502, "上游服务传输中断（" + e.getMessage() + "）");
        }
    }

    /** 目标 URL = 上游基址 + 剥掉 /api 的路径 + 原样 query string */
    private String buildTargetUrl(HttpServletRequest request) {
        String uri = request.getRequestURI();          // 形如 /api/documents（未解码，保留转义）
        String path = uri.length() >= 4 ? uri.substring(4) : "/";  // 剥 "/api"（4 字符）
        if (path.isEmpty()) {
            path = "/";
        }
        String query = request.getQueryString();
        return upstreamBase + path + (query != null ? "?" + query : "");
    }

    /** 上游请求 = 目标 URL + 透传的方法/头/体（剥 hop-by-hop） */
    private Request buildUpstreamRequest(HttpServletRequest request, String target) throws IOException {
        Request.Builder builder = new Request.Builder().url(target)
                .method(request.getMethod(), buildRequestBody(request));

        String contentType = request.getContentType();
        // 【坑】request.getHeaderNames() 每次调用都返回"新的"枚举——
        // 若在 while 条件里反复调用，nextElement() 永远拿到第一个头，循环永不终止。
        // 枚举必须先取一次存变量再遍历。
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(name) || REQ_CONTENT_LENGTH.equalsIgnoreCase(name)
                    || "Host".equalsIgnoreCase(name)) {
                continue;
            }
            // 同名多值头（如 Cookie 多行、Accept 多值）逐条追加，不丢值
            java.util.Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.addHeader(name, values.nextElement());
            }
        }
        // Content-Type 一定透传（multipart 的 boundary 在里面，丢了上传必炸）
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return builder.build();
    }

    /**
     * 请求体流式包装：GET/HEAD/DELETE 这类无体方法返回 null；
     * 有体方法用匿名 RequestBody 把 servlet 输入流"按需"喂给 OkHttp——
     * 大文件上传时内存里永远只有 8KB 缓冲，不是整个文件。
     */
    private RequestBody buildRequestBody(final HttpServletRequest request) throws IOException {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
            return null;
        }
        final MediaType mediaType = request.getContentType() != null
                ? MediaType.parse(request.getContentType())
                : null;
        final long contentLength = request.getContentLengthLong(); // -1 表示未知（chunked）
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return mediaType;
            }

            @Override
            public long contentLength() {
                return contentLength;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                InputStream in = request.getInputStream();
                Source source = Okio.source(in);
                long written = 0;
                try {
                    written = sink.writeAll(source);   // Okio 内部按段拷贝，不会整包进内存
                } finally {
                    // 诊断日志：声明的长度与实际写出字节数不一致是转发挂起的根因候选
                    log.info("proxy request body: declared contentLength={}, actually written={} (method={})",
                            contentLength, written, request.getMethod());
                    source.close();
                }
            }
        };
    }

    /** 执行上游调用并把状态码/头/体透传回浏览器（体走流式拷贝） */
    private void relay(Request upstreamRequest, HttpServletResponse response) throws IOException {
        try (Response upstream = httpClient.newCall(upstreamRequest).execute()) {
            response.setStatus(upstream.code());
            for (String name : upstream.headers().names()) {
                if (HOP_BY_HOP_HEADERS.contains(name) || RESP_CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    continue;
                }
                for (String value : upstream.headers(name)) {
                    response.addHeader(name, value);
                }
            }
            // 【流式透传核心】上游 body 是 InputStream，读 8KB 写 8KB——
            // SSE 场景下浏览器在整体完成前就能逐块收到数据（spec: 渐进式接收）
            InputStream body = upstream.body().byteStream();
            try {
                OutputStream out = response.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();   // 每块立即下发，不等缓冲区满（SSE 实时性的关键）
                }
            } finally {
                body.close();
            }
        }
    }

    /**
     * 网关自身错误：结构化信封（与 server 的 ApiResponse 同构），
     * 前端统一按 code≠0 处理，无需为网关错误单写一套解析。
     * code=5001 属 5xxx 系统段（server 内部错误占 5000，网关下游故障用 5001 区分）。
     */
    private void writeGatewayError(HttpServletResponse response, int httpStatus, String message) throws IOException {
        log.warn("代理失败: {} -> {}", httpStatus, message);
        writeEnvelopedError(response, httpStatus, 5001, message);
    }

    /**
     * 写统一信封的错误响应（isCommitted 防御：响应可能已部分写出，此时只能放弃改写，
     * 由容器补默认错误处理——正常转发流程不会走到这里，防御的是极端竞态）。
     * code 分段遵循全站约定：1xxx 参数、5xxx 系统。
     */
    private void writeEnvelopedError(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        if (!response.isCommitted()) {
            response.reset();
            response.setStatus(httpStatus);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    ("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
