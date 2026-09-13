package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Go 引擎 stdio 通道 —— 对标 StdioChannel，通过 stdin/stdout JSON 行协议驱动 Go 引擎。
 *
 * <p>【并发模型】与 StdioChannel 一致：
 * <ul>
 *   <li>synchronized call() 串行化请求——同一时刻只有一个请求在飞</li>
 *   <li>常驻读线程把响应行放入 BlockingQueue</li>
 *   <li>吞吐由批量接口消化</li>
 * </ul>
 *
 * <p>【与 StdioChannel 的差异】
 * <ul>
 *   <li>不需要 ping 探活——Go 引擎启动即就绪（无模型加载阶段）</li>
 *   <li>请求帧用 GoProtocol.Request（method + params），而非 op 名 + op 专用 payload</li>
 *   <li>不区分 Py4j/stdio Profile——Go 只有 stdio</li>
 * </ul>
 */
@Component
public class GoStdioChannel implements GoChannel {

    private static final Logger log = LoggerFactory.getLogger(GoStdioChannel.class);

    private final GoProcessLauncher launcher;
    private final long timeoutSeconds;

    private Process process;
    private BufferedWriter writer;
    private final BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(16);
    private final AtomicLong requestId = new AtomicLong();

    public GoStdioChannel(GoProcessLauncher launcher,
                          @Value("${engine.go.stdio-timeout-seconds:60}") long timeoutSeconds) {
        this.launcher = launcher;
        this.timeoutSeconds = timeoutSeconds;
    }

    @PostConstruct
    public void init() {
        try {
            start();
        } catch (IOException e) {
            throw new IllegalStateException("Go stdio channel start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String channelName() {
        return "go-stdio";
    }

    @Override
    public synchronized void start() throws IOException {
        if (isAlive()) return;

        process = launcher.launch();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // 常驻读线程：一行一个 JSON 响应帧 → 队列
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    responseQueue.offer(line);
                }
            } catch (Exception ignored) {
            } finally {
                responseQueue.offer("__PROCESS_EXITED__");
            }
        }, "toolbox-reader");
        reader.setDaemon(true);
        reader.start();

        // 启动握手（Go 引擎启动极快，3 次短轮询即可）
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                GoProtocol.Response resp = call("sys.ping", new HashMap<>());
                if (!resp.isError()) {
                    log.info("Go toolbox engine ready (channel={})", channelName());
                    return;
                }
            } catch (Exception e) {
                if (!isAlive()) {
                    throw new IOException("Go engine exited immediately, check [go-err] log", e);
                }
                sleepQuietly(500);
            }
        }
        throw new IOException("Go engine handshake failed after 3 attempts");
    }

    @Override
    public synchronized void close() {
        try {
            call("sys.shutdown", new HashMap<>());
        } catch (Exception ignored) {
        }
        launcher.stop(process);
        process = null;
        writer = null;
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public GoProtocol.Response send(String method, Map<String, Object> params) {
        return call(method, params);
    }

    /** 核心通信：序列化请求 → stdin → 队列取响应 → 反序列化 */
    private synchronized GoProtocol.Response call(String method, Map<String, Object> params) {
        long id = requestId.incrementAndGet();

        // 写请求到 stdin
        GoProtocol.Request req = new GoProtocol.Request();
        req.id = id;
        req.method = method;
        req.params = params;
        String json = GoProtocol.toJson(req);
        try {
            writer.write(json);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Go engine write failed: " + e.getMessage(), e);
        }

        // 从队列取响应（带超时）
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            String line = responseQueue.poll();
            if (line == null) {
                sleepQuietly(10);
                continue;
            }
            if ("__PROCESS_EXITED__".equals(line)) {
                throw new IllegalStateException("Go engine process exited unexpectedly");
            }
            GoProtocol.Response resp = GoProtocol.parseResponse(line);
            if (resp.id != id) {
                // 响应 id 不匹配（理论上串行模型不会发生，防御性处理）
                log.warn("response id mismatch: expected={} got={}", id, resp.id);
                continue;
            }
            if (resp.isError()) {
                throw new IllegalStateException(
                        "Go engine error [" + resp.error.code + "]: " + resp.error.message);
            }
            return resp;
        }

        throw new IllegalStateException(
                "Go engine request timed out after " + timeoutSeconds + "s: " + method);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void destroy() {
        close();
    }
}