package io.github.dekkerding.engine.infrastructure.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 通道 —— 子进程 stdin/stdout + JSON 行协议（零 socket）。
 *
 * <p>【Profile 切换】application.yml 里 spring.profiles.active=stdio 启用。
 * Py4J 在你的 Python 版本上出兼容问题时，切到这个通道即可照常工作——
 * 双通道抽象本身就是架构保险。
 *
 * <p>【并发模型】JDK 8 没有虚拟线程，这里用最简单的模型：
 * <ul>
 *   <li>互斥锁串行化请求（synchronized call()）——同一时刻只有一个请求在飞</li>
 *   <li>常驻读线程把响应行放进队列，call() 带超时取——避免 BufferedReader 阻塞读无法超时的问题</li>
 *   <li>吞吐压力由"批量接口"消化（embed 一次一批），单请求串行完全够用</li>
 * </ul>
 *
 * <p>【教学注释 · 为什么 stdout 不能桥接日志】stdio 协议里 stdout 每一行都是响应帧，
 * Python 端日志必须走 stderr（见 python/server_stdio.py 头部协议规范）。
 */
@Component
@Profile("stdio")
public class StdioChannel implements PythonChannel {

    private static final Logger log = LoggerFactory.getLogger(StdioChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PythonProcessLauncher launcher;
    private final long timeoutSeconds;

    private Process process;
    private BufferedWriter writer;
    /** 读线程 → call() 的响应交接队列（容量 16：正常一问一答，多余容量防御性缓冲） */
    private final BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(16);
    /** 请求 id 自增器：协议要求请求带 id，虽然串行模型下 id 恒可预测，显式自增更符合协议语义 */
    private final AtomicLong requestId = new AtomicLong();

    public StdioChannel(PythonProcessLauncher launcher,
                        @Value("${engine.python.stdio-timeout-seconds:60}") long timeoutSeconds) {
        this.launcher = launcher;
        this.timeoutSeconds = timeoutSeconds;
    }

    @PostConstruct
    public void init() {
        try {
            start();
        } catch (IOException e) {
            throw new IllegalStateException("stdio 通道启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String channelName() {
        return "stdio";
    }

    @Override
    public synchronized void start() throws IOException {
        if (isAlive()) {
            return;
        }
        // stdout 是协议专线：bridgeStdout 必须为 false
        process = launcher.launch("server_stdio.py", false);
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // 常驻读线程：一行一个响应帧，先进队列；进程退出时放毒丸让等待方立刻失败
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    responseQueue.offer(line);
                }
            } catch (Exception ignored) {
            } finally {
                responseQueue.offer("__PROCESS_EXITED__"); // 毒丸：读流结束=进程死了
            }
        }, "stdio-channel-reader");
        reader.setDaemon(true);
        reader.start();

        // 启动握手：ping 探活（轮询而非固定 sleep——首载模型可能要几十秒，蓝本 sleep(3000) 硬等待的教训）
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            try {
                call("ping", MAPPER.createObjectNode());
                log.info("stdio 通道就绪");
                return;
            } catch (Exception e) {
                if (!isAlive()) {
                    throw new IOException("Python 子进程启动后立即退出，检查 [python-err] 日志", e);
                }
                sleepQuietly(1000);
            }
        }
        throw new IOException("stdio 通道 " + timeoutSeconds + "s 内未就绪");
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            launcher.stop(process);
            process = null;
            writer = null;
        }
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    // ==================== 业务方法：全部走同一个 call() ====================

    @Override
    public PythonProtocol.EmbedBatch embedTexts(List<String> texts) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("texts", MAPPER.valueToTree(texts));
        String response = call("embed", payload);
        return PythonProtocol.parseEmbedBatch(response);
    }

    @Override
    public PythonProtocol.EmbedBatch embedImages(List<String> paths) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("paths", MAPPER.valueToTree(paths));
        return PythonProtocol.parseEmbedBatch(call("embed_images", payload));
    }

    @Override
    public PythonProtocol.EmbedBatch embedClipQuery(List<String> texts) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("texts", MAPPER.valueToTree(texts));
        return PythonProtocol.parseEmbedBatch(call("embed_clip_query", payload));
    }

    @Override
    public PythonProtocol.Annotation annotate(String imagePath, String filename, String caption) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("path", imagePath);
        payload.put("filename", filename == null ? "" : filename);
        payload.put("caption", caption == null ? "" : caption);
        return PythonProtocol.parseAnnotation(call("annotate", payload));
    }

    @Override
    public PythonProtocol.RerankScores rerank(String query, List<String> candidates) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.set("candidates", MAPPER.valueToTree(candidates));
        return PythonProtocol.parseRerankScores(call("rerank", payload));
    }

    @Override
    public PythonProtocol.Tokenize tokenize(String text, String mode) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", text);
        payload.put("mode", mode);
        return PythonProtocol.parseTokenize(call("tokenize", payload));
    }

    @Override
    public PythonProtocol.Keywords keywords(String text, int topK) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("text", text);
        payload.put("top_k", topK);
        return PythonProtocol.parseKeywords(call("keywords", payload));
    }

    @Override
    public PythonProtocol.Stats stats() {
        return PythonProtocol.parseStats(call("stats", MAPPER.createObjectNode()));
    }

    // ==================== 协议核心 ====================

    /**
     * 一问一答核心：写请求行 → 队列带超时取响应行 → 校验 id 与 ok 标志。
     * synchronized 串行化：协议无多路复用，并发请求在此天然排队。
     *
     * <p>【迟到帧自愈】前一次调用超时被放弃后，Python 迟到的响应仍会进队列——
     * 它的 id 小于当前请求 id。读到这种"过期帧"必须丢弃后继续等自己的帧，
     * 而不是报错（否则错位永远追不回来：每次都读到上一次的残留，通道假死）。
     */
    private synchronized String call(String op, ObjectNode payload) {
        if (!isAlive() || writer == null) {
            throw EngineException.downstream("stdio 通道未运行", null);
        }
        long id = requestId.incrementAndGet();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("id", id);
        request.put("op", op);
        request.set("payload", payload);

        try {
            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush(); // 管道有缓冲，必须 flush 对端才能读到
        } catch (IOException e) {
            throw EngineException.downstream("stdio 请求写入失败(op=" + op + ")", e);
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (true) {
            String line;
            try {
                long waitMillis = deadline - System.currentTimeMillis();
                line = waitMillis <= 0 ? null : responseQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw EngineException.downstream("stdio 等待响应被中断", e);
            }
            if (line == null) {
                throw EngineException.downstream("stdio 响应超时(" + timeoutSeconds + "s, op=" + op + ")", null);
            }
            if ("__PROCESS_EXITED__".equals(line)) {
                throw EngineException.downstream("Python 子进程已退出", null);
            }

            try {
                ObjectNode response = (ObjectNode) MAPPER.readTree(line);
                long responseId = response.path("id").asLong();
                if (responseId < id) {
                    // 迟到的过期帧（属于某次已超时/已放弃的调用）：丢弃，继续等自己的
                    log.warn("丢弃迟到的 stdio 响应帧 id={}（当前请求 id={}, op={}）——前次调用超时的残留",
                            responseId, id, op);
                    continue;
                }
                if (responseId > id) {
                    // 未来帧：串行模型下不该出现（读到尚未发出的请求的响应）——协议已乱，报错复位
                    throw EngineException.downstream("stdio 响应 id 错乱: 收到 " + responseId + " > 期望 " + id, null);
                }
                if (!response.path("ok").asBoolean()) {
                    ObjectNode error = (ObjectNode) response.get("error");
                    String type = error == null ? "Unknown" : error.path("type").asText();
                    String message = error == null ? line : error.path("message").asText();
                    throw EngineException.downstream("Python 错误[" + type + "]: " + message, null);
                }
                // 返回 payload 的 JSON 字符串，由调用方反序列化成对应 DTO
                return response.get("payload").toString();
            } catch (EngineException e) {
                throw e;
            } catch (Exception e) {
                throw EngineException.downstream("stdio 响应解析失败: " + line, e);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
