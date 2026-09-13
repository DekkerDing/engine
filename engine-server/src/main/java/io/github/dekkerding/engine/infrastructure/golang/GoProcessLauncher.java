package io.github.dekkerding.engine.infrastructure.golang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Go 子进程启动器 —— Go 引擎通道的"进程管家"（镜像 {@code PythonProcessLauncher}）。
 *
 * <p>职责：解析当前平台 → 定位/解压对应平台二进制 → ProcessBuilder 拉起 →
 * stderr 桥接日志。协议（stdin/stdout JSON 行）归 {@link StdioGoChannel} 管，
 * 进程细节归本类——两关注者分离（DRY，与 python 通道同一分工）。
 *
 * <p>【二进制定位顺序】（优先级从高到低）
 * <ol>
 *   <li>配置 engine.go.home 指向的二进制文件（测试/开发直指 go build 产物）</li>
 *   <li>jar 内 classpath:/golang/&lt;platform&gt;/toolbox(.exe) → 解压到
 *       ./go-runtime/&lt;platform&gt;/（生产态；每次启动校验大小，变化才重解压）</li>
 * </ol>
 * Go 与 Python 不同：Go 编译出<strong>平台原生二进制</strong>（无解释器），
 * 所以按 os.name/os.arch 选目录是第一职责；开发态可绕过解压直接指 home。
 */
@Component
public class GoProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(GoProcessLauncher.class);

    /** 解压目标根目录（.gitignore 已排除，clean 即还原） */
    static final String RUNTIME_DIR = "go-runtime";

    private final String configuredHome;

    public GoProcessLauncher(@Value("${engine.go.home:}") String configuredHome) {
        this.configuredHome = configuredHome;
    }

    /** 拉起 Go 引擎子进程（stdout 是协议专线，只桥 stderr）。 */
    public Process launch() throws IOException {
        File binary = resolveBinary();
        ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
        pb.directory(new File("."));
        Process process = pb.start();
        log.info("Go 引擎子进程已拉起: {} (pid={})", binary.getAbsolutePath(), pid(process));
        bridgeToLog(process.getErrorStream(), "[go-err] ");
        return process;
    }

    /** 定位当前平台应执行的二进制文件（解析顺序见类注释）。 */
    public File resolveBinary() throws IOException {
        String platform = currentPlatform();
        String binaryName = binaryName(platform);

        if (configuredHome != null && !configuredHome.isEmpty()) {
            File direct = new File(configuredHome);
            // home 可以指二进制本身，也可以指平台目录（容纳两种习惯）
            File asFile = direct.isFile() ? direct : new File(direct, binaryName);
            if (asFile.isFile()) {
                return asFile;
            }
            throw new IOException("engine.go.home 配置的二进制不存在: " + asFile.getAbsolutePath());
        }
        return ensureExtracted(platform, binaryName);
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
                    + "开发态可用 engine.go.home 直指 go build 产物）");
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

    /** 把子进程 stderr 逐行转进 SLF4J（守护线程，与 python 通道同款）。 */
    private void bridgeToLog(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("{}{}", prefix, line);
                }
            } catch (Exception ignored) {
                // 进程退出时流关闭走到这里，属正常路径
            }
        }, "go-output-bridge");
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

    /** 停止进程：优雅 → 5 秒 → 强杀（与 PythonProcessLauncher 同款防线）。 */
    public void stop(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Go 引擎子进程 5 秒未退出，已强制终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
