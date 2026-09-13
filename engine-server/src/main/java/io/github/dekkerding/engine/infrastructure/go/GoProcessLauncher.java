package io.github.dekkerding.engine.infrastructure.go;

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
import java.util.concurrent.TimeUnit;

/**
 * Go 二进制进程启动器 —— 对标 PythonProcessLauncher 的"进程管家"。
 *
 * <p>与 PythonProcessLauncher 的关键差异：
 * <ul>
 *   <li>Go 是编译后的二进制（toolbox / toolbox.exe），不是脚本文件</li>
 *   <li>Go 不需要环境变量配置模型 key / 端口（纯工具方法，无模型依赖）</li>
 *   <li>Go 只有 stdio 一种协议（无 bridgeStdout 参数——stdout 始终是协议专线）</li>
 *   <li>Go 不需要 PYTHONIOENCODING / PYTHONUNBUFFERED 这类 Python 专属环境变量</li>
 * </ul>
 *
 * <p>【二进制查找顺序】（优先级从高到低）
 * <ol>
 *   <li>配置 engine.go.binary（显式指定，测试常用）</li>
 *   <li>环境变量 ENGINE_GO_BINARY</li>
 *   <li>./golang/toolbox —— 模块内 Go 源码（go run 开发态，需 go 命令可用）</li>
 *   <li>./golang/toolbox.exe —— Windows 发布态（go build 产出物）</li>
 * </ol>
 *
 * <p>【教学注释 · Go vs Python 进程启动的差异】
 *   Python 启动慢（加载模型权重、初始化 Py4J Gateway 可能需要 30s+），
 *   所以 PythonProcessLauncher 有 ping 轮询探活逻辑。
 *   Go 工具引擎启动几乎瞬时（无模型加载），启动即就绪。
 */
@Component
public class GoProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(GoProcessLauncher.class);

    private final String configuredBinary;
    private final String goCommand;

    public GoProcessLauncher(@Value("${engine.go.binary:}") String configuredBinary,
                             @Value("${engine.go.command:go}") String goCommand) {
        this.configuredBinary = configuredBinary;
        this.goCommand = goCommand;
    }

    /**
     * 定位 Go 二进制文件或入口源码。
     * 返回 ProcessBuilder 规格数组：[命令, 参数...]（与 PythonProcessLauncher 的 launch 对齐）。
     *
     * <p>运行模式：
     * <ol>
     *   <li>如果定位到已编译二进制（toolbox.exe / toolbox），直接启动二进制</li>
     *   <li>如果只找到源码入口（main.go），用 go run 启动（开发态，需 Go SDK）</li>
     * </ol>
     */
    public ProcessBuilder resolveAndBuild() throws IOException {
        // 1. 配置/环境变量优先
        if (configuredBinary != null && !configuredBinary.isEmpty()) {
            File bin = new File(configuredBinary);
            if (bin.isFile()) {
                log.info("Go 二进制定位: 配置 engine.go.binary → {}", bin.getAbsolutePath());
                return new ProcessBuilder(bin.getAbsolutePath());
            }
            throw new IOException("配置的 Go 二进制不存在: " + bin.getAbsolutePath());
        }
        String envBinary = System.getenv("ENGINE_GO_BINARY");
        if (envBinary != null && !envBinary.isEmpty()) {
            File bin = new File(envBinary);
            if (bin.isFile()) {
                log.info("Go 二进制定位: 环境变量 ENGINE_GO_BINARY → {}", bin.getAbsolutePath());
                return new ProcessBuilder(bin.getAbsolutePath());
            }
            throw new IOException("环境变量 ENGINE_GO_BINARY 指定的二进制不存在: " + bin.getAbsolutePath());
        }

        // 2. 探测源码目录
        Map<String, File> candidates = new LinkedHashMap<>();
        candidates.put("go build 产出", new File("golang/toolbox.exe"));
        candidates.put("go build(Linux)", new File("golang/toolbox"));
        candidates.put("go run 入口", new File("golang/cmd/toolbox/main.go"));

        for (Map.Entry<String, File> e : candidates.entrySet()) {
            File f = e.getValue();
            if (f.isFile()) {
                log.info("Go 入口定位: {} → {}", e.getKey(), f.getAbsolutePath());
                // 区分二进制启动和源码启动
                if (f.getName().endsWith(".go")) {
                    // 源码入口 → go run
                    return buildGoRun(f);
                } else {
                    // 编译好的二进制
                    return new ProcessBuilder(f.getAbsolutePath());
                }
            }
        }

        throw new IOException(
                "未找到 Go 引擎入口。已探测: " + candidates.values()
                + "。请先执行: cd golang && go build -o toolbox ./cmd/toolbox/");
    }

    /** go run 模式：用 Go 命令启动源码（开发态，无需手动编译） */
    private ProcessBuilder buildGoRun(File mainGo) {
        ProcessBuilder pb = new ProcessBuilder(goCommand, "run", ".");
        pb.directory(mainGo.getParentFile()); // 工作目录 = cmd/toolbox/
        log.info("Go 启动模式: go run（开发态，源码入口 {}）", mainGo.getAbsolutePath());
        return pb;
    }

    /**
     * 拉起 Go 子进程。
     * stdout 是协议专线不能碰，stderr 桥接到日志。
     */
    public Process launch() throws IOException {
        ProcessBuilder pb = resolveAndBuild();
        pb.directory(pb.directory() != null ? pb.directory() : new File("."));

        // 关键环境变量：GOTOOLBOX_STDIO=1 告诉 Go 端走 stdio 协议（而非其他模式）
        pb.environment().put("GOTOOLBOX_STDIO", "1");

        Process process = pb.start();
        log.info("Go 引擎子进程已拉起 (pid={})", pid(process));

        // stderr 桥接到日志：Go 端所有日志走 stderr，stdout 是协议专线
        bridgeToLog(process.getErrorStream(), "[go-err] ");

        return process;
    }

    /** 把子进程 stderr 逐行转进 SLF4J */
    private void bridgeToLog(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("{}{}", prefix, line);
                }
            } catch (Exception ignored) {
                // 进程被销毁时流正常关闭
            }
        }, "toolbox-stderr-bridge");
        t.setDaemon(true);
        t.start();
    }

    /** 获取子进程 PID（反射拿，JDK 8 兼容） */
    private long pid(Process process) {
        try {
            java.lang.reflect.Field f = process.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            return (Long) f.get(process);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 停止进程：优雅停止 → 5s 超时 → 强制终止 */
    public void stop(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Go 引擎子进程 5s 未退出，已强制终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** LinkedHashMap 保持插入顺序（探测日志按顺序输出） */
    private static class LinkedHashMap<K, V> extends java.util.LinkedHashMap<K, V> {
        LinkedHashMap() { super(); }
    }
}