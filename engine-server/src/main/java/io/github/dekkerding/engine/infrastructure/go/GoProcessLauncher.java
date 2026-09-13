package io.github.dekkerding.engine.infrastructure.go;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Go 引擎子进程启动器 —— 通道的"进程管家"（对齐 {@code PythonProcessLauncher} 分工）。
 *
 * <p>【二进制定位链】（优先级从高到低，覆盖开发/测试/生产三态）
 * <ol>
 *   <li>配置 engine.go.binary 直指二进制（测试/开发最短路径）</li>
 *   <li>环境变量 ENGINE_GO_BINARY</li>
 *   <li>./golang/toolbox(.exe) —— 本地 go build 产物直用</li>
 *   <li>开发态 go run —— 源码在位且装了 Go，免预编译（教学友好）</li>
 *   <li>jar 内 classpath:/golang/&lt;platform&gt;/ → 解压 ./go-runtime/&lt;platform&gt;/
 *       （生产态：bootJar 把双平台二进制打进了 jar，见 build.gradle 的 packageGolang）</li>
 * </ol>
 * 找不到就抛带修复指引的异常——"失败信息要能指导修复"是运维友好第一原则。
 *
 * <p>【装配条件】engine.go.enabled=true 才成为 Bean：默认配置下零 Bean 零进程零解压，
 * spec「默认零变化」的装配层保证。
 */
@Component
@ConditionalOnProperty(name = "engine.go.enabled", havingValue = "true")
public class GoProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(GoProcessLauncher.class);

    /** 解压目标根目录（.gitignore 排除，clean 即还原） */
    static final String RUNTIME_DIR = "go-runtime";

    private final String configuredBinary;
    private final String goCommand;

    public GoProcessLauncher(@Value("${engine.go.binary:}") String configuredBinary,
                             @Value("${engine.go.command:go}") String goCommand) {
        this.configuredBinary = configuredBinary;
        this.goCommand = goCommand;
    }

    /** 定位可启动的进程（stderr 桥日志；stdout 是协议专线不许碰）。 */
    public Process launch() throws IOException {
        ProcessBuilder pb = resolve();
        pb.directory(pb.directory() != null ? pb.directory() : new File("."));
        Process process = pb.start();
        log.info("Go 引擎子进程已拉起: {} (pid={})", pb.command(), pid(process));
        bridgeToLog(process.getErrorStream(), "[go-err] ");
        return process;
    }

    /** 组装定位链命中的 ProcessBuilder（见类注释顺序）。 */
    public ProcessBuilder resolve() throws IOException {
        if (configuredBinary != null && !configuredBinary.isEmpty()) {
            File bin = new File(configuredBinary);
            if (bin.isFile()) {
                return new ProcessBuilder(bin.getAbsolutePath());
            }
            throw new IOException("engine.go.binary 配置的二进制不存在: " + bin.getAbsolutePath());
        }
        String env = System.getenv("ENGINE_GO_BINARY");
        if (env != null && !env.isEmpty()) {
            File bin = new File(env);
            if (bin.isFile()) {
                return new ProcessBuilder(bin.getAbsolutePath());
            }
        }
        String platform = currentPlatform();
        File toolExe = new File("golang", binaryName(platform));
        if (toolExe.isFile()) {
            return new ProcessBuilder(toolExe.getAbsolutePath());
        }
        File mainGo = new File("golang/cmd/toolbox/main.go");
        if (mainGo.isFile()) {
            // 开发轨：go run .（模块根的 go.mod 会被 Go 向上检索到）
            ProcessBuilder pb = new ProcessBuilder(goCommand, "run", ".");
            pb.directory(mainGo.getParentFile());
            return pb;
        }
        File extracted = ensureExtracted(platform, binaryName(platform));
        return new ProcessBuilder(extracted.getAbsolutePath());
    }

    /**
     * 从 classpath 解压二进制到 ./go-runtime/&lt;platform&gt;/（大小变化才重解压）。
     *
     * <p>【为什么校验大小而不是哈希】二进制是构建产物、随 jar 整体替换——
     * jar 换版本时 classpath 资源与磁盘副本大小几乎必然不同；逐字节哈希
     * 对 MB 级文件是纯开销。大小相同即认定未变（误判率工程上可忽略）。
     */
    File ensureExtracted(String platform, String binaryName) throws IOException {
        ClassPathResource resource = new ClassPathResource("golang/" + platform + "/" + binaryName);
        if (!resource.exists()) {
            throw new IOException("classpath 内未找到 Go 引擎二进制: golang/" + platform + "/"
                    + binaryName + "（jar 是否含双平台产物？见 build.gradle 的 packageGolang；"
                    + "开发态可用 engine.go.binary 直指 go build 产物）");
        }
        File target = new File(RUNTIME_DIR + "/" + platform + "/" + binaryName);
        if (target.isFile() && target.length() == resource.contentLength()) {
            return target; // 已解压且未变，直接复用
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建 Go 运行时目录: " + parent.getAbsolutePath());
        }
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        // 【非 Windows 必须】Linux 解压出的二进制无执行位——chmod +x
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            if (!target.setExecutable(true)) {
                throw new IOException("无法为 Go 二进制加执行位: " + target.getAbsolutePath());
            }
        }
        log.info("Go 引擎二进制已解压: {} ({} 字节)", target.getAbsolutePath(), target.length());
        return target;
    }

    /** 当前 JVM 平台对应的目录名（windows-amd64 / linux-amd64 / ...）。 */
    static String currentPlatform() {
        return platformDir(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** 平台目录解析（纯函数，单测直接覆盖）：os.name/os.arch → go 风格目录名。 */
    static String platformDir(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String platform;
        if (os.contains("windows")) {
            platform = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "darwin";
        } else {
            platform = "linux"; // 兜底：容器/主流发行版
        }
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (arch.equals("x86_64") || arch.equals("amd64")) {
            return platform + "-amd64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return platform + "-arm64";
        }
        return platform + "-" + arch; // 少见架构按原名（riscv64 等）
    }

    /** 二进制文件名：仅 Windows 带扩展名（Go 编译惯例）。 */
    static String binaryName(String platform) {
        return platform.startsWith("windows") ? "toolbox.exe" : "toolbox";
    }

    /** 把子进程 stderr 逐行转进 SLF4J（守护线程，Go 端日志全走 stderr）。 */
    private void bridgeToLog(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("{}{}", prefix, line);
                }
            } catch (Exception ignored) {
                // 进程退出时流关闭走到这里，属正常路径
            }
        }, "toolbox-stderr");
        t.setDaemon(true);
        t.start();
    }

    private long pid(Process process) {
        try {
            // Process.pid() 是 JDK 9+；本项目基线 JDK 8——反射拿 Windows 句柄
            // （与 PythonProcessLauncher 同款；拿不到返回 -1 不影响功能）
            java.lang.reflect.Field f = process.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            return (Long) f.get(process);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 停止进程：先 destroy() 优雅停止，最多等 5 秒，超时 destroyForcibly（防僵尸进程）。 */
    public void stop(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Go 引擎子进程 5 秒未退出，已强制终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
