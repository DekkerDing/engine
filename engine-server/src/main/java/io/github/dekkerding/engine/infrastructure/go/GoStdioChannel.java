package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.engine.GoToolboxStatus;
import io.github.dekkerding.engine.domain.repository.GoToolboxStatusQuery;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>【故障语义（合并自已测初版，spec「进程生命周期与故障语义」）】
 * <ul>
 *   <li>毒丸：读线程见 EOF 投 {@code __PROCESS_EXITED__}，在途/后续调用立即失败</li>
 *   <li>超时：poll(剩余时间) 阻塞等待——不忙等不烧 CPU；超时即抛 EngineException</li>
 *   <li>迟到帧：超时被放弃的调用，其响应帧晚到时 id &lt; 当前期望——记 warn 丢弃，
 *       绝不误配给下一个调用（串行模型下的防御性自愈）</li>
 *   <li>错误帧：转 {@link EngineException#downstream}——走全局异常体系，
 *       调用方拿到的是与 Python 通道同纪律的"下游引擎错误"</li>
 * </ul>
 *
 * <p>【装配条件】engine.go.enabled=true 才成为 Bean：默认配置零进程零 Bean，
 * 「默认零变化」的通道层保证（手写初版的无条件 @Component 会在无 Go 环境炸启动，已修）。
 */
@Component
@ConditionalOnProperty(name = "engine.go.enabled", havingValue = "true")
public class GoStdioChannel implements GoChannel, GoToolboxStatusQuery {

    private static final Logger log = LoggerFactory.getLogger(GoStdioChannel.class);

    /** 读线程投递的毒丸行：进程退出后在途调用立即失败（对齐 StdioChannel） */
    static final String POISON_PILL = "__PROCESS_EXITED__";

    /** 测试注入口：生产态由 Spring 注入的 launcher 供应，测试态直给假进程 */
    interface ProcessSupplier {
        Process get() throws IOException;
    }

    private final ProcessSupplier processSupplier;
    private final long timeoutSeconds;

    private Process process;
    private BufferedWriter writer;
    private final BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(16);
    private final AtomicLong requestId = new AtomicLong();

    /** 生产构造：Spring 装配（launcher 拉起真实 Go 子进程）。 */
    @Autowired
    public GoStdioChannel(GoProcessLauncher launcher,
                          @Value("${engine.go.stdio-timeout-seconds:60}") long timeoutSeconds) {
        this(launcher::launch, timeoutSeconds);
    }

    /** 测试构造：注入假进程供应器（FakeGoToolbox 等），不起真实引擎。 */
    GoStdioChannel(ProcessSupplier processSupplier, long timeoutSeconds) {
        this.processSupplier = processSupplier;
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

        Process p = processSupplier.get();
        process = p;
        writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

        // 常驻读线程：一行一个 JSON 响应帧 → 队列（捕获局部 p，规避 close() 置空竞态）
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    responseQueue.offer(line);
                }
            } catch (Exception ignored) {
                // 进程退出时流关闭走到这里，属正常路径
            } finally {
                responseQueue.offer(POISON_PILL);
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
            // 关闭路径尽人事：发不出（进程已死/已关）就直接走 stop
        }
        if (process != null) {
            launcherLikeStop(process);
        }
        process = null;
        writer = null;
    }

    /** 生产态经 launcher.stop（5s 优雅 + 强杀）；测试态进程自管，这里只 destroy。 */
    private void launcherLikeStop(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    @Override
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    @Override
    public GoProtocol.Response send(String method, Map<String, Object> params) {
        return call(method, params);
    }

    // ========== GoToolboxStatusQuery（健康聚合端口） ==========

    /**
     * 健康探活（端口合同：永不抛异常，失败折叠为 ok=false + lastError）。
     *
     * <p>【实现者为什么是通道而非 GoToolboxProvider】健康段反映的是<b>第一级</b>开关
     * （engine.go.enabled——通道在场性）的状态：场景二（vector.* 副本加速）只开第一级、
     * 没有 go-toolbox Profile 时 GoToolboxProvider 不在场，但 Go 引擎确实在跑——
     * 探活挂在第二级会把"在跑"误报成 N/A。Python 侧先例同款：Py4jChannel/StdioChannel
     * 实现 EngineStatusQuery。
     *
     * <p>【UP 的定义】通道存活 <b>且</b> sys.stats 真实往返成功。只看 isAlive 不够：
     * 读线程活着不代表 Go 能响应请求（卡死的进程照样"活着"）；sys.stats 在 Go 端
     * 是纯内存读（微秒级），真实探活且不构成健康接口的负担。
     */
    @Override
    public GoToolboxStatus status() {
        if (!isAlive()) {
            return GoToolboxStatus.down("Go 进程未运行（未启动或已退出）");
        }
        try {
            GoProtocol.Response resp = call("sys.stats", new HashMap<>());
            GoProtocol.StatsResult stats =
                    GoProtocol.extractResult(resp, GoProtocol.StatsResult.class);
            return new GoToolboxStatus(true, stats.version, stats.vector_count,
                    stats.tools, null);
        } catch (Exception e) {
            return GoToolboxStatus.down("sys.stats 探活失败: " + e.getMessage());
        }
    }

    /** 核心通信：序列化请求 → stdin → 队列取响应 → 反序列化。 */
    private synchronized GoProtocol.Response call(String method, Map<String, Object> params) {
        if (writer == null) {
            throw EngineException.downstream("Go 通道未运行（未启动或已关闭），method=" + method);
        }
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
            throw EngineException.downstream("Go 引擎写入失败: " + e.getMessage());
        }

        // 阻塞式限时等待：poll(剩余毫秒)——不忙等（合并自已测初版的免忙等硬化）
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw EngineException.downstream(
                        "Go 引擎调用超时（" + timeoutSeconds + "s）: " + method);
            }
            String line;
            try {
                line = responseQueue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw EngineException.downstream("等待 Go 引擎响应被中断: " + method);
            }
            if (line == null) {
                continue; // poll 到点返回 null——循环顶再判剩余时间，精确超时
            }
            if (POISON_PILL.equals(line)) {
                throw EngineException.downstream("Go 引擎进程已退出（调用 " + method + " 失败）");
            }
            GoProtocol.Response resp = GoProtocol.parseResponse(line);
            if (resp.id < id) {
                // 迟到帧：上一次超时被放弃的调用的响应——丢弃自愈，绝不误配
                log.warn("丢弃迟到响应帧: id={}（当前期望 {}）", resp.id, id);
                continue;
            }
            if (resp.id > id) {
                // 串行模型下不该出现——出现即协议错乱，快败好过错配
                throw EngineException.downstream(
                        "Go 通道响应帧错乱: 期望 id=" + id + " 实际=" + resp.id);
            }
            if (resp.isError()) {
                throw EngineException.downstream(
                        "Go 引擎错误 [code=" + resp.error.code + "]: " + resp.error.message);
            }
            return resp;
        }
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
