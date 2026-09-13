package io.github.dekkerding.engine.infrastructure.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Python 子进程启动器 —— 两个通道共用的"进程管家"。
 *
 * <p>职责：定位脚本目录 → ProcessBuilder 拉起 → 子进程输出桥接到日志。
 * 把这些从通道实现里抽出来，Py4j/Stdio 两个通道只管协议，不管进程细节（DRY）。
 *
 * <p>【脚本目录查找顺序】（优先级从高到低）
 * <ol>
 *   <li>配置 engine.python.home（显式指定，测试常用）</li>
 *   <li>环境变量 ENGINE_PYTHON_HOME</li>
 *   <li>./python —— 源码位于本模块内 engine-server/python，bootRun 工作目录在
 *       engine-server/ 下时命中（开发态）</li>
 *   <li>./python-runtime —— jar 部署时 PythonBootstrap 解压目录（生产态）</li>
 * </ol>
 * 找不到就抛异常并列出全部探测路径——"失败信息要能指导修复"是运维友好第一原则。
 */
@Component
public class PythonProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(PythonProcessLauncher.class);

    private final String pythonCommand;
    private final String configuredHome;
    private final String modelKey;
    private final int py4jPort;

    public PythonProcessLauncher(@Value("${engine.python.command:python}") String pythonCommand,
                                 @Value("${engine.python.home:}") String configuredHome,
                                 @Value("${engine.python.model-key:text-embedding-zh}") String modelKey,
                                 @Value("${engine.python.py4j-port:25335}") int py4jPort) {
        this.pythonCommand = pythonCommand;
        this.configuredHome = configuredHome;
        this.modelKey = modelKey;
        this.py4jPort = py4jPort;
    }

    /** 定位 python/ 脚本目录（见类注释的查找顺序） */
    public File resolveScriptDir() {
        Map<String, File> candidates = new HashMap<>();
        if (configuredHome != null && !configuredHome.isEmpty()) {
            candidates.put("配置 engine.python.home", new File(configuredHome));
        }
        String envHome = System.getenv("ENGINE_PYTHON_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            candidates.put("环境变量 ENGINE_PYTHON_HOME", new File(envHome));
        }
        candidates.put("./python（模块内源码，bootRun 开发态）", new File("python"));
        candidates.put("./python-runtime（jar 生产态）", new File("python-runtime"));

        for (Map.Entry<String, File> e : candidates.entrySet()) {
            // 以入口文件存在为准（目录存在但没脚本等于没有）
            if (new File(e.getValue(), "server_stdio.py").isFile()) {
                log.info("Python 脚本目录定位成功: {} ({})", e.getValue().getAbsolutePath(), e.getKey());
                return e.getValue();
            }
        }
        throw new IllegalStateException(
                "未找到 Python 脚本目录，已探测: " + candidates.values()
                        + "。开发态请在仓库根目录启动；或用 engine.python.home 显式指定");
    }

    /**
     * 拉起 Python 子进程。
     *
     * @param scriptName  入口脚本名（server_py4j.py / server_stdio.py）
     * @param bridgeStdout true=把 stdout 也桥接进日志（Py4J 模式）；
     *                     false=stdout 是协议专线不许碰（stdio 模式），只桥 stderr
     */
    public Process launch(String scriptName, boolean bridgeStdout) throws IOException {
        File script = new File(resolveScriptDir(), scriptName);
        if (!script.isFile()) {
            throw new IOException("Python 入口脚本不存在: " + script.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(pythonCommand, script.getAbsolutePath());
        pb.directory(script.getParentFile());

        // 【关键】子进程环境变量：
        // PYTHONIOENCODING  强制 UTF-8（Windows 默认 GBK，中文请求会乱码）
        // PYTHONUNBUFFERED  关闭输出缓冲（stdio 协议不 flush 对端就读不到，双保险）
        // HF_ENDPOINT       模型下载走国内镜像（Python 端也有 setdefault 兜底）
        // ENGINE_TEXT_MODEL_KEY  文本模型键（双模型切换：text-embedding-zh | text-embedding-multilingual）
        // ENGINE_PY4J_PORT       Py4J 监听端口（Python 端 server_py4j.py 读它——两端端口从此同源）
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("HF_ENDPOINT", "https://hf-mirror.com");
        pb.environment().put("ENGINE_TEXT_MODEL_KEY", modelKey);
        pb.environment().put("ENGINE_PY4J_PORT", String.valueOf(py4jPort));

        Process process = pb.start();
        log.info("Python 子进程已拉起: {} {} (pid={})", pythonCommand, scriptName, pid(process));

        // stderr 始终桥接到日志（Python 端所有日志走 stderr，两个通道一致）
        bridgeToLog(process.getErrorStream(), "[python-err] ");
        if (bridgeStdout) {
            bridgeToLog(process.getInputStream(), "[python-out] ");
        }
        return process;
    }

    /** 把子进程输出流逐行转进 SLF4J（守护线程：不阻塞 JVM 退出，也不阻塞主流程） */
    private void bridgeToLog(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("{}{}", prefix, line);
                }
            } catch (Exception ignored) {
                // 进程被销毁时流会正常关闭走到这里，无需处理
            }
        }, "python-output-bridge");
        t.setDaemon(true);
        t.start();
    }

    private long pid(Process process) {
        try {
            // Process.pid() 是 JDK 9+；JDK 8 反射拿句柄（拿不到也不影响功能）
            java.lang.reflect.Field f = process.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            return (Long) f.get(process);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 停止进程：先 destroy() 优雅停止，最多等 5 秒，超时 destroyForcibly（防僵尸进程占端口） */
    public void stop(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Python 子进程 5 秒未退出，已强制终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
