package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.model.resource.TextDocumentResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 双通道集成测试 —— 拉起真实 Python 子进程验证协议闭环。
 *
 * <p>【测试策略 · 三层金字塔在本项目的落地】
 * <ul>
 *   <li>单元测试（快、常跑）：纯 Java 逻辑，如余弦相似度、分块器</li>
 *   <li>集成测试（本类，需要 Python 环境）：验证跨语言协议</li>
 *   <li>端到端（人工/脚本）：浏览器点页面</li>
 * </ul>
 *
 * <p>【环境自适应】默认跳过，设置环境变量 ENGINE_IT=1 时启用：
 * 这样 CI 没有 Python 环境不会误红，本机想跑就 set ENGINE_IT=1。
 * 【教学注释】用 main 方法 + 断言而非 JUnit 注解驱动，便于教学阅读；
 * 真正的 JUnit 版本在测试体系完善阶段（P7）补齐。
 */
public class PythonChannelIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PythonChannelIntegrationTest.class);

    public static void main(String[] args) throws Exception {
        // 工作目录兜底：无论从哪启动都以仓库根为基准定位 python/
        File repoRoot = findRepoRoot();
        System.setProperty("engine.python.home", new File(repoRoot, "python").getAbsolutePath());

        int failures = 0;
        failures += testChannel("stdio", new StdioChannelFactory());
        failures += testChannel("py4j", new Py4jChannelFactory());
        failures += testFailover();

        log.info("========== 集成测试结束: {} 失败 ==========", failures);
        System.exit(failures);
    }

    private interface ChannelFactory {
        PythonChannel create() throws Exception;
    }

    private static class StdioChannelFactory implements ChannelFactory {
        public PythonChannel create() throws Exception {
            PythonProcessLauncher launcher = new PythonProcessLauncher("python",
                    System.getProperty("engine.python.home"), "text-embedding-zh", 25335);
            StdioChannel channel = new StdioChannel(launcher, 120);
            channel.start();
            return channel;
        }
    }

    private static class Py4jChannelFactory implements ChannelFactory {
        public PythonChannel create() throws Exception {
            PythonProcessLauncher launcher = new PythonProcessLauncher("python",
                    System.getProperty("engine.python.home"), "text-embedding-zh", 25335);
            Py4jChannel channel = new Py4jChannel(launcher, 25335, 120000, 120);
            channel.start();
            return channel;
        }
    }

    /**
     * failover 通道测试：主通道死亡 → 自动回退 stdio → 主通道恢复 → 自动切回。
     * 【场景对齐】specs/python-embedding-engine 的"主通道故障自动回退"与"不双开常驻进程"。
     */
    private static int testFailover() throws Exception {
        log.info("---------- 通道 [failover] 开始测试 ----------");
        int failures = 0;
        // 独立端口 25336：避开 py4j 段刚释放的 25335（Windows TIME_WAIT 窗口会短暂阻塞同端口重绑）
        // 恢复探测周期传 3 秒（生产默认 30）：测试无需长等
        FailoverChannel channel = new FailoverChannel(
                new PythonProcessLauncher("python", System.getProperty("engine.python.home"),
                        "text-embedding-zh", 25336),
                25336, 120000, 120, 120, 3);
        try {
            channel.start();
            check("初始走主通道 py4j", "py4j".equals(channel.channelName()));

            // 1. 主通道正常编码
            check("主通道编码成功",
                    channel.embedTexts(java.util.Arrays.asList("回退测试文本")).vectors.size() == 1);

            // 2. 杀主通道（close 是彻底关闭：健康线程退出，不会自愈重启）
            channel.primaryChannel().close();

            // 3. 下一次调用应自动回退 stdio 并成功
            check("回退后编码成功",
                    channel.embedTexts(java.util.Arrays.asList("回退后编码文本")).vectors.size() == 1);
            check("当前通道=stdio(failover)", "stdio(failover)".equals(channel.channelName()));

            // 4. 主通道复活（手动 start 模拟自愈成功）→ 探测周期内切回
            channel.primaryChannel().start();
            Thread.sleep(5000);
            check("主通道恢复后切回", "py4j".equals(channel.channelName()));
            check("切回后编码正常",
                    channel.embedTexts(java.util.Arrays.asList("切回后编码文本")).vectors.size() == 1);

            log.info("通道 [failover] 全部通过 ✓");
        } catch (Exception e) {
            failures++;
            log.error("通道 [failover] 测试异常", e);
        } finally {
            channel.close();
        }
        return failures;
    }

    private static int testChannel(String name, ChannelFactory factory) throws Exception {
        log.info("---------- 通道 [{}] 开始测试 ----------", name);
        PythonChannel channel = null;
        int failures = 0;
        try {
            channel = factory.create();

            // 1. 存活
            check("isAlive", channel.isAlive());

            // 2. 分词
            PythonProtocol.Tokenize tokenize = channel.tokenize("红塔与小花", "precise");
            check("tokenize 非空", tokenize != null && !tokenize.tokens.isEmpty());
            log.info("tokenize 结果: {}", tokenize.tokens);

            // 3. 关键词
            PythonProtocol.Keywords keywords = channel.keywords("红塔下面开着小花", 3);
            check("keywords 非空", keywords != null && !keywords.keywords.isEmpty());

            // 4. 批量编码（默认模型 bge-small-zh-v1.5：dim=512、确定性；模型不可用则显式降级）
            PythonProtocol.EmbedBatch batch = channel.embedTexts(
                    java.util.Arrays.asList("红塔与小花", "公园里的红色宝塔"));
            check("dim=512(默认 bge)", batch.dim == 512);
            check("向量条数", batch.vectors.size() == 2);
            check("模型可用或明确降级", batch.degraded || batch.model.contains("bge"));

            // 5. 同文本同向量（确定性）。
            // 【浮点教学点】同一文本在不同 batch 大小下编码，GEMM 运算顺序不同会有低位微差，
            // 位级 equals 会误报——正确的"确定性"标准是余弦相似度 > 0.999
            PythonProtocol.EmbedBatch again = channel.embedTexts(java.util.Arrays.asList("红塔与小花"));
            double cosine = cosine(batch.vectors.get(0), again.vectors.get(0));
            check("确定性(cosine=" + String.format("%.6f", cosine) + ")", cosine > 0.999);

            // 6. 状态查询
            PythonProtocol.Stats stats = channel.stats();
            check("stats 注册表含双文本模型",
                    stats.registry != null && stats.registry.containsKey("text-embedding-zh")
                            && stats.registry.containsKey("text-embedding-multilingual"));

            // 7. 领域端口适配层（ChannelEmbeddingProvider 的核心路径）
            List<TextDocumentResource> resources = new ArrayList<>();
            resources.add(new TextDocumentResource("doc-1", 0, "向量化引擎测试文本"));
            resources.add(new TextDocumentResource("doc-1", 1, "红塔公园的小花"));
            ChannelEmbeddingProvider provider = new ChannelEmbeddingProvider(channel, 16, "text-embedding-zh");
            List<Embedding> embeddings = provider.embedBatch(resources);
            check("provider 输出条数", embeddings.size() == 2);
            check("provider 维度", embeddings.get(0).getDimension() == 512);
            check("provider 状态", provider.status().isOk());

            log.info("通道 [{}] 全部通过 ✓", name);
        } catch (Exception e) {
            failures++;
            log.error("通道 [{}] 测试异常", name, e);
        } finally {
            if (channel != null) {
                channel.close();
            }
        }
        return failures;
    }

    /** 余弦相似度（测试工具版；生产版在 domain 的 VectorizationDomainService，P4 实现） */
    private static double cosine(List<Double> a, List<Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            log.info("  [PASS] {}", label);
        } else {
            log.error("  [FAIL] {}", label);
            throw new AssertionError("断言失败: " + label);
        }
    }

    private static File findRepoRoot() {
        // python/ 已迁入 engine-server 模块内：从当前目录向上找含 python/ 的目录即可命中
        // （channelIT 的工作目录是 engine-server/，其下就是 python/）
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null && !new File(dir, "python").isDirectory()) {
            dir = dir.getParentFile();
        }
        if (dir == null) {
            throw new IllegalStateException("未找到 python/ 脚本目录（应位于 engine-server/python）");
        }
        return dir;
    }
}
