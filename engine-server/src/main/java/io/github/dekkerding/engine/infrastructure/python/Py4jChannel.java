package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import py4j.ClientServer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Py4J 通道 —— 本机环回 TCP（127.0.0.1:25335），默认通道。
 *
 * <p>【满足"不通过网络"约束的界定】Py4J 走本机环回 socket：流量不出机器、
 * 不经网卡/网线、不占用外部网络带宽，也不是 HTTP——与你 vectordemon 规范
 * "禁止 HTTP 引擎间通信，必须 Py4J"一脉相承。
 *
 * <p>【边界协议】与 Python 端 server_py4j.py 对齐：方法传参/返回都是 JSON 字符串
 * （Java 用这个接口的方法名精确匹配 Python facade 的 camelCase 方法）。
 */
@Component
@Profile("py4j")
public class Py4jChannel implements PythonChannel {

    private static final Logger log = LoggerFactory.getLogger(Py4jChannel.class);

    /**
     * Java 侧入口接口 —— 方法名与 python/server_py4j.py 的 EngineFacade 一一对应。
     * Py4J 按方法名远程调用；这里只出现 String/List 等简单类型（复杂结构装在 JSON 字符串里）。
     */
    public interface PythonEntryPoint {
        String ping();
        String getStats();
        String embedTexts(List<String> texts);
        String embedImages(List<String> paths);
        String embedClipQuery(List<String> texts);
        String annotateImage(String path, String filename, String caption);
        String rerank(String query, List<String> candidates);
        String tokenize(String text, String mode);
        String keywords(String text, int topK);
    }

    private final PythonProcessLauncher launcher;
    private final int port;
    private final long startupTimeoutMs;
    private final long callTimeoutSeconds;

    private ClientServer clientServer;
    private Process process;
    private volatile PythonEntryPoint entryPoint;
    private volatile boolean running = false;

    public Py4jChannel(PythonProcessLauncher launcher,
                       @Value("${engine.python.py4j-port:25335}") int port,
                       @Value("${engine.python.startup-timeout-ms:60000}") long startupTimeoutMs,
                       @Value("${engine.python.call-timeout-seconds:60}") long callTimeoutSeconds) {
        this.launcher = launcher;
        this.port = port;
        this.startupTimeoutMs = startupTimeoutMs;
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    @PostConstruct
    public void init() {
        try {
            start();
            startHealthCheck();
        } catch (IOException | RuntimeException e) {
            // Bean 创建失败时 @PreDestroy 不会执行，必须在这里清理孤儿进程（蓝本踩过的坑）。
            // RuntimeException 一并捕获：连接竞态类异常若漏网会直接遗留 Python 孤儿进程
            close();
            throw new IllegalStateException("Py4J 通道启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String channelName() {
        return "py4j";
    }

    @Override
    public synchronized void start() throws IOException {
        if (isAlive()) {
            return;
        }
        // Py4J 模式 stdout 无协议职责，可桥接日志
        process = launcher.launch("server_py4j.py", true);

        // 轮询连接：Python 端 ClientServer 构造完才开始监听，拉起需要时间
        long deadline = System.currentTimeMillis() + startupTimeoutMs;
        Py4JException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                connect();
                log.info("Py4J 通道就绪 (127.0.0.1:{})", port);
                running = true;
                return;
            } catch (Py4JException e) {
                lastError = e;
                if (!process.isAlive()) {
                    throw new IOException("Python 子进程启动后立即退出，检查 [python-err] 日志", e);
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Py4J 连接等待被中断", ie);
                }
            }
        }
        throw new IOException("Py4J 通道 " + startupTimeoutMs + "ms 内未就绪: "
                + (lastError == null ? "" : lastError.getMessage()));
    }

    private void connect() {
        // 【蓝本踩坑记录】ClientServer 无参构造默认连 25334，必须显式 pythonPort(port)
        //
        // 【异常遮蔽坑】本类底部定义了内部类 Py4JException（无包名前缀时简单名
        // 指向它），而 py4j 库原生异常是 py4j.Py4JException——两者同名。start()
        // 的轮询只 catch 内部类，若这里放任原生异常抛出，"Python 未监听完"这种
        // 本应重试等待的瞬态失败会直接炸掉 Bean（实测：冷启动竞态偶发启动失败，
        // Python 子进程成孤儿）。因此在这里统一包装成内部异常再抛
        //
        // 【半成品清理坑】ClientServer 构造过程中 Java 侧会先绑定回调端口（默认
        // 25334）再去连 Python——连接 Python 失败时这个半成品若不 shutdown，
        // 端口被它占着，轮询的后续每次重试都在"绑定回调端口"这步失败（异常
        // message 为 null），表现是"Python 明明已 listening 却永远连不上"。
        // 因此失败路径必须销毁半成品，只用局部变量、成功后才落字段
        ClientServer cs = null;
        try {
            cs = new ClientServer.ClientServerBuilder()
                    .pythonPort(port)
                    .build();
            PythonEntryPoint ep = (PythonEntryPoint) cs.getPythonServerEntryPoint(
                    new Class<?>[]{PythonEntryPoint.class});
            if (ep == null) {
                throw new Py4JException("Python 入口对象为 null");
            }
            // 连接成功≠能服务：立即 ping 一次验证入口方法可调用
            ep.ping();
            // 全部成功才落字段（供业务调用与 close 清理）
            clientServer = cs;
            entryPoint = ep;
        } catch (py4j.Py4JException e) {
            if (cs != null) {
                try {
                    cs.shutdown();
                } catch (Exception ignore) {
                    // 半成品关闭失败不影响重试
                }
            }
            // 瞬态连接失败（Python 仍在启动）→ 转为内部异常交给轮询重试
            throw new Py4JException("py4j 连接未就绪: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        entryPoint = null;
        if (clientServer != null) {
            try {
                clientServer.shutdown();
            } catch (Exception e) {
                log.warn("Py4J clientServer 关闭异常: {}", e.getMessage());
            }
            clientServer = null;
        }
        launcher.stop(process);
        process = null;
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive() && entryPoint != null;
    }

    // ==================== 业务方法：统一入口校验 + JSON 边界 ====================

    @Override
    public PythonProtocol.EmbedBatch embedTexts(List<String> texts) {
        return PythonProtocol.parseEmbedBatch(invoke(() -> entryPoint.embedTexts(texts), "embedTexts"));
    }

    @Override
    public PythonProtocol.EmbedBatch embedImages(List<String> paths) {
        return PythonProtocol.parseEmbedBatch(invoke(() -> entryPoint.embedImages(paths), "embedImages"));
    }

    @Override
    public PythonProtocol.EmbedBatch embedClipQuery(List<String> texts) {
        return PythonProtocol.parseEmbedBatch(invoke(() -> entryPoint.embedClipQuery(texts), "embedClipQuery"));
    }

    @Override
    public PythonProtocol.Annotation annotate(String imagePath, String filename, String caption) {
        // caption/filename 归一：null/空白 → 空串（Python 侧约定空串=未提供；
        // filename 空串时 Python 回退 path 的 stem 派生，兼容旧语义）
        String cap = caption == null ? "" : caption;
        String fname = filename == null ? "" : filename;
        return PythonProtocol.parseAnnotation(
                invoke(() -> entryPoint.annotateImage(imagePath, fname, cap), "annotateImage"));
    }

    @Override
    public PythonProtocol.RerankScores rerank(String query, List<String> candidates) {
        return PythonProtocol.parseRerankScores(invoke(() -> entryPoint.rerank(query, candidates), "rerank"));
    }

    @Override
    public PythonProtocol.Tokenize tokenize(String text, String mode) {
        return PythonProtocol.parseTokenize(invoke(() -> entryPoint.tokenize(text, mode), "tokenize"));
    }

    @Override
    public PythonProtocol.Keywords keywords(String text, int topK) {
        return PythonProtocol.parseKeywords(invoke(() -> entryPoint.keywords(text, topK), "keywords"));
    }

    @Override
    public PythonProtocol.Stats stats() {
        return PythonProtocol.parseStats(invoke(entryPoint::getStats, "getStats"));
    }

    /** 统一调用包装：入口就绪检查 + 异常翻译（Py4JJavaException → EngineException 503） */
    private String invoke(Py4jCall call, String op) {
        PythonEntryPoint ep = entryPoint;
        if (ep == null) {
            throw EngineException.downstream("Py4J 通道未就绪(op=" + op + ")", null);
        }
        try {
            return call.get();
        } catch (Exception e) {
            // Py4J 把 Python 端异常包装成 Py4JJavaException，细节在 message 里
            throw EngineException.downstream("Py4J 调用失败(op=" + op + "): " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface Py4jCall {
        String get();
    }

    /** 连接阶段的内部异常（不对外暴露 py4j 类型） */
    private static class Py4JException extends RuntimeException {
        Py4JException(String message) {
            super(message);
        }
    }

    // ==================== 健康检查与自动恢复 ====================

    /**
     * 守护线程：每 10 秒探活，进程死亡则自动重启（沿用蓝本已验证的策略）。
     * 【教学注释 · volatile running】健康线程与业务线程共享该标志，
     * volatile 保证可见性——这是 JDK 8 下最轻量的线程安全手段。
     */
    private void startHealthCheck() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    TimeUnit.SECONDS.sleep(10);
                    if (!running) {
                        break;
                    }
                    if (process == null || !process.isAlive()) {
                        log.error("Python 子进程已死亡，尝试自动重启...");
                        restart();
                    } else {
                        entryPoint.ping(); // 进程活着也要验证协议层能响应
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("健康检查失败，尝试重启通道: {}", e.getMessage());
                    try {
                        restart();
                    } catch (Exception ex) {
                        log.error("通道重启失败，等待下轮健康检查重试: {}", ex.getMessage());
                    }
                }
            }
        }, "py4j-health-check");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void restart() throws IOException {
        log.warn("重启 Py4J 通道（先清理旧进程/连接）...");
        close();
        start();
    }
}
