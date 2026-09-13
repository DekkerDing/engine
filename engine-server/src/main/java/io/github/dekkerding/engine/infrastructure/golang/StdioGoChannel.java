package io.github.dekkerding.engine.infrastructure.golang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dekkerding.engine.domain.exception.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 通道 —— Go 引擎子进程的 stdin/stdout + JSON 行协议（镜像 {@code StdioChannel}，
 * 继承其全部已验证智慧：常驻读线程 + 响应队列 + 毒丸 + 串行 call + 迟到帧自愈）。
 *
 * <p>【与 python stdio 通道的三处协议差异】
 * <ul>
 *   <li>字段名：python 用 {id, op, payload, ok}；Go 用 {id, method, params} +
 *       {id, result | error:{code,message}}（见 golang/internal/protocol）</li>
 *   <li>握手：python "ping" 可能等模型加载几十秒；Go 二进制毫秒级启动，
 *       "sys.ping" 一两次轮询即就绪——保留轮询骨架，超时通常只防进程起不来</li>
 *   <li>stdout 专线：两端一致，Go 端日志已走 stderr（启动横幅亦然）</li>
 * </ul>
 *
 * <p>【装配条件】engine.go.enabled=true 才装配（默认 false 零装配——
 * 与 python 通道用 @Profile 切换是同一思想的配置化变体）。
 *
 * <p>【并发模型】与 python 通道同款：JDK 8 无虚拟线程，synchronized call()
 * 串行化请求；Go 引擎的并行发生在处理函数内部（goroutine 分片），
 * 通道层保持一问一答的简单性——"进程间串行、进程内并行"是 D3 的既定分工。
 */
@Component
@ConditionalOnProperty(name = "engine.go.enabled", havingValue = "true")
public class StdioGoChannel implements GoChannel {

    private static final Logger log = LoggerFactory.getLogger(StdioGoChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 毒丸：读线程发现流结束（=进程死亡）时入队，让所有等待方立刻失败 */
    static final String POISON_PILL = "__PROCESS_EXITED__";

    /** 进程工厂：生产环境用 GoProcessLauncher，测试注入假进程（FakeGoToolbox） */
    private final ProcessSupplier processSupplier;
    private final long timeoutSeconds;
    private final long startupTimeoutSeconds;

    private Process process;
    private BufferedWriter writer;
    private final BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(16);
    private final AtomicLong requestId = new AtomicLong();

    /** 生产构造：经 GoProcessLauncher 拉起真实 Go 二进制。 */
    public StdioGoChannel(GoProcessLauncher launcher,
                          @Value("${engine.go.call-timeout-seconds:60}") long timeoutSeconds,
                          @Value("${engine.go.startup-timeout-seconds:30}") long startupTimeoutSeconds) {
        this(launcher::launch, timeoutSeconds, startupTimeoutSeconds);
    }

    /** 测试构造：注入任意进程工厂（假进程/短超时的构造点）。 */
    StdioGoChannel(ProcessSupplier processSupplier, long timeoutSeconds, long startupTimeoutSeconds) {
        this.processSupplier = processSupplier;
        this.timeoutSeconds = timeoutSeconds;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
    }

    /** 进程工厂的函数式接口（对比 Go：这就是一个 func 类型别名）。 */
    interface ProcessSupplier {
        Process launch() throws IOException;
    }

    @PostConstruct
    public void init() {
        try {
            start();
        } catch (IOException e) {
            throw new IllegalStateException("Go stdio 通道启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String channelName() {
        return "go-stdio";
    }

    @Override
    public synchronized void start() throws IOException {
        if (isAlive()) {
            return;
        }
        responseQueue.clear(); // 重启语义：旧进程的残留帧一律作废
        process = processSupplier.launch();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // 常驻读线程：一行一个响应帧，先进队列；流结束投毒丸
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    responseQueue.offer(line);
                }
            } catch (Exception ignored) {
            } finally {
                responseQueue.offer(POISON_PILL);
            }
        }, "go-channel-reader");
        reader.setDaemon(true);
        reader.start();

        // 启动握手：sys.ping 轮询探活（Go 毫秒级启动，通常第一发即中）
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(startupTimeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            try {
                call("sys.ping", MAPPER.createObjectNode());
                log.info("Go stdio 通道就绪（sys.ping 握手成功）");
                return;
            } catch (Exception e) {
                if (!isAlive()) {
                    throw new IOException("Go 引擎子进程启动后立即退出，检查 [go-err] 日志", e);
                }
                sleepQuietly(500);
            }
        }
        throw new IOException("Go stdio 通道 " + startupTimeoutSeconds + "s 内未就绪");
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (process != null) {
            // 先发优雅谢幕帧再 destroy：让 Go 侧日志完整落盘（超时强杀是兜底）
            try {
                if (writer != null) {
                    writer.write(MAPPER.writeValueAsString(MAPPER.createObjectNode()
                            .put("id", requestId.incrementAndGet())
                            .put("method", "sys.shutdown")));
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ignored) {
                // 进程已死时的常规路径，交给 destroy 收尾
            }
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
            writer = null;
        }
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * 一问一答核心（迟到帧自愈逻辑与 python 通道同源，注释从简——
     * 详见 StdioChannel.call 的完整论证）：前次超时 abandoned 的响应帧
     * id 小于当前请求——丢弃继续等；未来帧是协议错乱——报错复位。
     */
    @Override
    public synchronized String call(String method, ObjectNode params) {
        if (!isAlive() || writer == null) {
            throw EngineException.downstream("Go 通道未运行（引擎不可用）", null);
        }
        long id = requestId.incrementAndGet();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("id", id);
        request.put("method", method);
        if (params != null && params.size() > 0) {
            request.set("params", params);
        }

        try {
            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw EngineException.downstream("Go 请求写入失败(method=" + method + ")", e);
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (true) {
            String line;
            try {
                long waitMillis = deadline - System.currentTimeMillis();
                line = waitMillis <= 0 ? null : responseQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw EngineException.downstream("Go 等待响应被中断", e);
            }
            if (line == null) {
                throw EngineException.downstream("Go 响应超时(" + timeoutSeconds + "s, method=" + method + ")", null);
            }
            if (POISON_PILL.equals(line)) {
                throw EngineException.downstream("Go 引擎子进程已退出", null);
            }
            try {
                JsonNode response = MAPPER.readTree(line);
                long responseId = response.path("id").asLong();
                if (responseId < id) {
                    log.warn("丢弃迟到的 Go 响应帧 id={}（当前请求 id={}, method={}）", responseId, id, method);
                    continue;
                }
                if (responseId > id) {
                    throw EngineException.downstream("Go 响应 id 错乱: 收到 " + responseId + " > 期望 " + id, null);
                }
                JsonNode error = response.get("error");
                if (error != null) {
                    throw EngineException.downstream("Go 错误[code=" + error.path("code").asInt() + "]: "
                            + error.path("message").asText(), null);
                }
                return response.get("result").toString();
            } catch (EngineException e) {
                throw e;
            } catch (Exception e) {
                throw EngineException.downstream("Go 响应解析失败: " + line, e);
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
