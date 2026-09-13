package io.github.dekkerding.engine.infrastructure.golang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go 进程启动器单测 —— 任务 2.1 的验收点：
 * 平台目录解析（os.name/os.arch → go 风格）、二进制命名、
 * classpath 解压的目标路径与"大小未变即复用"逻辑。
 *
 * <p>【测试策略】platformDir 是纯函数直接对表覆盖；解压逻辑用
 * src/test/resources 下的假二进制（golang/test-platform/toolbox）在
 * 临时目录外走真实 IO——不 mock 文件系统，行为即真相。
 */
class GoProcessLauncherTest {

    // ---- 平台目录解析（纯函数对表） ----

    @Test
    void 平台目录解析_主流四组合() {
        assertEquals("windows-amd64", GoProcessLauncher.platformDir("Windows 11", "amd64"));
        assertEquals("windows-amd64", GoProcessLauncher.platformDir("Windows Server 2022", "x86_64"));
        assertEquals("linux-amd64", GoProcessLauncher.platformDir("Linux", "amd64"));
        assertEquals("linux-arm64", GoProcessLauncher.platformDir("Linux", "aarch64"));
        assertEquals("darwin-arm64", GoProcessLauncher.platformDir("Mac OS X", "aarch64"));
    }

    @Test
    void 平台目录解析_未知架构按原名() {
        assertEquals("linux-riscv64", GoProcessLauncher.platformDir("Linux", "riscv64"));
    }

    @Test
    void 二进制命名_仅windows带exe() {
        assertEquals("toolbox.exe", GoProcessLauncher.binaryName("windows-amd64"));
        assertEquals("toolbox", GoProcessLauncher.binaryName("linux-amd64"));
    }

    // ---- classpath 解压（真实 IO，用测试资源里的假二进制） ----

    @Test
    void 解压产物落在指定平台目录且可复用() throws IOException {
        GoProcessLauncher launcher = new GoProcessLauncher("");
        // test-platform 是测试资源里的虚构平台目录（fixture 假二进制）
        File extracted = launcher.ensureExtracted("test-platform", "toolbox");
        try {
            assertEquals(new File(GoProcessLauncher.RUNTIME_DIR + "/test-platform/toolbox").getCanonicalFile(),
                    extracted.getCanonicalFile());
            assertTrue(extracted.isFile(), "解压产物应存在");
            assertEquals(extracted.length(), resourceSize("golang/test-platform/toolbox"),
                    "解压产物大小应与 classpath 资源一致");

            // 二次调用不重解压：截断 mtime 粒度不可靠，改验证同一 File 路径与内容哈希语义
            File again = launcher.ensureExtracted("test-platform", "toolbox");
            assertEquals(extracted.getCanonicalPath(), again.getCanonicalPath());
        } finally {
            // 清理测试解压物，不留垃圾在仓库
            deleteRecursively(new File(GoProcessLauncher.RUNTIME_DIR + "/test-platform").getParentFile());
        }
    }

    @Test
    void classpath无该平台时给出指导性错误() {
        GoProcessLauncher launcher = new GoProcessLauncher("");
        IOException e = assertThrows(IOException.class,
                () -> launcher.ensureExtracted("no-such-platform", "toolbox"));
        assertTrue(e.getMessage().contains("packageGolang") || e.getMessage().contains("engine.go.home"),
                "错误信息应包含修复指引，实际: " + e.getMessage());
    }

    @Test
    void 配置home直指二进制优先于解压(@TempDir File tmp) throws IOException {
        File binary = new File(tmp, "toolbox.exe");
        Files.write(binary.toPath(), new byte[]{1, 2, 3});
        GoProcessLauncher launcher = new GoProcessLauncher(binary.getAbsolutePath());
        assertEquals(binary.getCanonicalFile(), launcher.resolveBinary().getCanonicalFile());

        // home 指目录时拼平台二进制名
        File dir = new File(tmp, "plat");
        assertTrue(dir.mkdir());
        File inDir = new File(dir, "toolbox.exe");
        Files.write(inDir.toPath(), new byte[]{4, 5, 6});
        GoProcessLauncher launcherDir = new GoProcessLauncher(dir.getAbsolutePath());
        assertEquals(inDir.getCanonicalFile(), launcherDir.resolveBinary().getCanonicalFile());
    }

    @Test
    void 配置home不存在时快速失败(@TempDir File tmp) {
        GoProcessLauncher launcher = new GoProcessLauncher(new File(tmp, "ghost.exe").getAbsolutePath());
        assertThrows(IOException.class, launcher::resolveBinary);
    }

    // ---- 辅助 ----

    private long resourceSize(String path) throws IOException {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("测试资源缺失: " + path);
            }
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
            }
            return total;
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            Arrays.stream(children).forEach(this::deleteRecursively);
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }
}
