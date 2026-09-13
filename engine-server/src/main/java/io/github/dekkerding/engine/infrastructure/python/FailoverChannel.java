package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 双通道故障转移选择器 —— Py4J（主）→ stdio（惰性备）自动回退（用户决策：双通道融合）。
 *
 * <p>【状态机】（见 design.md D5）
 * <pre>
 *   正常: active = py4j
 *   py4j 调用失败 ──惰性拉起──▶ active = stdio(failover)      // 不常驻双进程
 *   恢复探测: 备用期间周期检查 py4j 是否已被其自愈线程救活
 *            ──isAlive──▶ active = py4j，并关闭 stdio         // 切回不双开
 * </pre>
 *
 * <p>【为什么组合而不继承】FailoverChannel 持有两个 {@link PythonChannel} 实现，
 * 对 Spring 只暴露一个 PythonChannel Bean——上层（ChannelEmbeddingProvider）
 * 完全无感知通道切换。这是"装饰器 + 策略"的组合：本类不实现任何编码逻辑，
 * 只做"选哪个通道"这一件事。
 *
 * <p>【生命周期归一】两个子通道由本类 new 出来（非 Spring 管理，各自的
 * &#64;PostConstruct 不会触发），启动/停止全部集中在本类的注解方法里——
 * 避免"Spring 启动期就拉起 stdio"违反惰性原则。
 *
 * <p>【切换线程安全】call() 失败路径经 switchLock 串行化：并发首失败只触发
 * 一次备用拉起；active 用 volatile 保证读路径无锁快走。
 */
@Component
@Profile("failover")
public class FailoverChannel implements PythonChannel {

    private static final Logger log = LoggerFactory.getLogger(FailoverChannel.class);

    private final Py4jChannel primary;
    private final StdioChannel backup;
    /** 恢复探测周期（秒）：测试注入小值，生产默认 30 */
    private final long recoverIntervalSeconds;
    private final Object switchLock = new Object();

    private volatile PythonChannel active;
    private volatile boolean running = false;
    private Thread recoverThread;

    public FailoverChannel(PythonProcessLauncher launcher,
                           @Value("${engine.python.py4j-port:25335}") int py4jPort,
                           @Value("${engine.python.startup-timeout-ms:60000}") long startupTimeoutMs,
                           @Value("${engine.python.call-timeout-seconds:60}") long callTimeoutSeconds,
                           @Value("${engine.python.stdio-timeout-seconds:60}") long stdioTimeoutSeconds,
                           @Value("${engine.python.recover-interval-seconds:30}") long recoverIntervalSeconds) {
        this.primary = new Py4jChannel(launcher, py4jPort, startupTimeoutMs, callTimeoutSeconds);
        this.backup = new StdioChannel(launcher, stdioTimeoutSeconds);
        this.recoverIntervalSeconds = recoverIntervalSeconds;
    }

    @PostConstruct
    public void init() {
        try {
            start();
        } catch (IOException | RuntimeException e) {
            // RuntimeException 一并捕获：主通道启动竞态类异常漏网会直接遗留 Python 孤儿
            close();
            throw new IllegalStateException("FailoverChannel 启动失败: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        close();
    }

    @Override
    public String channelName() {
        PythonChannel ch = active;
        // 备用期间名字带标记：健康页一眼看出"当前在跑的是回退通道"
        return ch == primary ? primary.channelName() : backup.channelName() + "(failover)";
    }

    @Override
    public synchronized void start() throws IOException {
        primary.start();
        active = primary;
        running = true;
        startRecoverLoop();
    }

    @Override
    public void close() {
        running = false;
        backup.close();
        primary.close();
        active = null;
    }

    @Override
    public boolean isAlive() {
        PythonChannel ch = active;
        return ch != null && ch.isAlive();
    }

    // ==================== 业务方法：统一走 call() 故障转移 ====================

    @Override
    public PythonProtocol.EmbedBatch embedTexts(List<String> texts) {
        return call(ch -> ch.embedTexts(texts), "embedTexts");
    }

    @Override
    public PythonProtocol.EmbedBatch embedImages(List<String> paths) {
        return call(ch -> ch.embedImages(paths), "embedImages");
    }

    @Override
    public PythonProtocol.EmbedBatch embedClipQuery(List<String> texts) {
        return call(ch -> ch.embedClipQuery(texts), "embedClipQuery");
    }

    @Override
    public PythonProtocol.Annotation annotate(String imagePath, String filename, String caption) {
        return call(ch -> ch.annotate(imagePath, filename, caption), "annotate");
    }

    @Override
    public PythonProtocol.RerankScores rerank(String query, List<String> candidates) {
        return call(ch -> ch.rerank(query, candidates), "rerank");
    }

    @Override
    public PythonProtocol.Tokenize tokenize(String text, String mode) {
        return call(ch -> ch.tokenize(text, mode), "tokenize");
    }

    @Override
    public PythonProtocol.Keywords keywords(String text, int topK) {
        return call(ch -> ch.keywords(text, topK), "keywords");
    }

    @Override
    public PythonProtocol.Stats stats() {
        return call(PythonChannel::stats, "stats");
    }

    /**
     * 故障转移核心：先走当前通道；失败则（锁内）确认/触发备用切换后重试一次。
     * 备用也失败 → 抛出聚合了两通道原因的下游异常（不吞错）。
     */
    private <T> T call(Function<PythonChannel, T> op, String name) {
        PythonChannel first = active;
        if (first == null) {
            throw EngineException.downstream("通道未启动(op=" + name + ")", null);
        }
        try {
            return op.apply(first);
        } catch (Exception firstFailure) {
            PythonChannel second = switchAfterFailure(first, firstFailure, name);
            if (second == null || second == first) {
                throw firstFailure; // 已在备用通道上失败且无切换发生：如实抛出首次异常
            }
            try {
                return op.apply(second);
            } catch (Exception secondFailure) {
                throw EngineException.downstream("双通道均失败(op=" + name + "): 主["
                        + firstFailure.getMessage() + "] 备[" + secondFailure.getMessage() + "]", secondFailure);
            }
        }
    }

    /**
     * 失败后的通道选择（锁内三选一）：
     * 1. active 已不再是失败的那条 → 并发切换发生过（如 recover 线程切回主通道
     *    并关闭了 stdio）：返回当前 active 让调用方在新通道上重试——
     *    【e2e 实测教训】不能只比较 active != primary：worker 持 backup 调用失败
     *    （stdio 恰被 recover 关闭）而 active 已切回 primary 时，旧逻辑会把这次
     *    失败误判为"主通道失败"，重复拉起 stdio 并在握手竞态下二次失败；
     * 2. 失败的是主通道 → 惰性拉起 stdio 并切换；
     * 3. 失败的就是备用且无并发切换 → 返回 null（无路可切，如实失败）。
     */
    private PythonChannel switchAfterFailure(PythonChannel failed, Exception cause, String op) {
        synchronized (switchLock) {
            PythonChannel ch = active;
            if (ch != failed) {
                return ch; // 失败期间通道已被并发切换：到"现在在岗的"通道上重试
            }
            if (failed != primary) {
                return null; // 本就在备用上失败且无切换：无备可切
            }
            log.warn("主通道(py4j)调用失败(op={})，惰性回退 stdio: {}", op, cause.getMessage());
            try {
                if (!backup.isAlive()) {
                    backup.start(); // 惰性拉起：平时绝不双开 torch 进程
                }
                active = backup;
                return backup;
            } catch (Exception startFailure) {
                throw EngineException.downstream("主通道失败且备用通道拉起失败: "
                        + startFailure.getMessage(), cause);
            }
        }
    }

    /**
     * 恢复探测：备用期间每 recoverIntervalSeconds 检查主通道是否复活
     * （Py4jChannel 自带健康线程会自动重启进程，这里只负责"发现并切回"）。
     * 切回后立即关闭备用进程——回到"不双开"的稳态。
     */
    private void startRecoverLoop() {
        recoverThread = new Thread(() -> {
            while (running) {
                try {
                    TimeUnit.SECONDS.sleep(recoverIntervalSeconds);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!running || active != backup || !primary.isAlive()) {
                    continue;
                }
                synchronized (switchLock) {
                    // 双检：锁外判断后锁内条件可能已变（例如并发切回）
                    if (running && active == backup && primary.isAlive()) {
                        active = primary;
                        backup.close();
                        log.info("主通道(py4j)已恢复，切回主通道并关闭备用 stdio 进程");
                    }
                }
            }
        }, "python-channel-recover");
        recoverThread.setDaemon(true);
        recoverThread.start();
    }

    // ==================== 测试观察口（package-private，非公开 API） ====================

    /** 集成测试用：当前活跃通道引用（用于断言回退/切回状态） */
    PythonChannel activeChannel() {
        return active;
    }

    /** 集成测试用：主通道引用（用于模拟主通道死亡/恢复） */
    Py4jChannel primaryChannel() {
        return primary;
    }
}
