package io.github.dekkerding.engine.infrastructure.golang;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go stdio 通道集成测试（假进程版）—— 任务 2.2 的验收点：
 * call→响应往返、超时分支、错误帧翻译、未知方法不致命、
 * 优雅关闭后通道不可用（毒丸分支在 2.5 的杀进程场景覆盖）。
 *
 * <p>【假进程策略】用本 JVM 的 java 可执行文件拉起 {@link FakeGoToolbox}
 * 作为协议对端——不依赖 Go 工具链在场，跨平台一致（Windows/Linux CI 同绿）。
 */
class StdioGoChannelTest {

    private StdioGoChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
    }

    private StdioGoChannel newChannel(long timeoutSeconds) {
        return new StdioGoChannel(fakeProcessSupplier(), timeoutSeconds, 15);
    }

    /** 用当前 JVM 的 java 启动 FakeGoToolbox——协议行为完全可控的假对端。 */
    private StdioGoChannel.ProcessSupplier fakeProcessSupplier() {
        return () -> {
            String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin"
                    + java.io.File.separator + "java";
            String classpath = System.getProperty("java.class.path");
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classpath,
                    FakeGoToolbox.class.getName());
            pb.redirectErrorStream(false);
            return pb.start();
        };
    }

    @Test
    void 启动握手与往返() throws IOException {
        channel = newChannel(5);
        channel.start();
        assertTrue(channel.isAlive(), "假进程应存活");

        String result = channel.call("sys.ping", null);
        assertTrue(result.contains("pong"), "sys.ping 应返回 pong，实际: " + result);

        String echo = channel.call("echo.upper", null);
        assertTrue(echo.contains("ECHO"));
    }

    @Test
    void 超时分支() throws IOException {
        channel = newChannel(1);
        channel.start();
        EngineException e = assertThrows(EngineException.class,
                () -> channel.call("sleep.forever", null));
        assertTrue(e.getMessage().contains("超时"), "应报超时，实际: " + e.getMessage());
        // 超时后通道仍可用（迟到帧自愈）：紧跟一次正常调用
        String result = channel.call("sys.ping", null);
        assertTrue(result.contains("pong"), "超时后的下一次调用应恢复正常（迟到帧被丢弃）");
    }

    @Test
    void 未知方法回错误帧且通道存活() throws IOException {
        channel = newChannel(5);
        channel.start();
        EngineException e = assertThrows(EngineException.class,
                () -> channel.call("no.such.method", null));
        assertTrue(e.getMessage().contains("1001"), "错误帧应带 code=1001，实际: " + e.getMessage());
        assertTrue(channel.isAlive(), "未知方法不应杀死引擎");
        assertTrue(channel.call("sys.ping", null).contains("pong"));
    }

    @Test
    void 优雅关闭后不可用() throws IOException {
        channel = newChannel(5);
        channel.start();
        channel.close();
        assertFalse(channel.isAlive());
        EngineException e = assertThrows(EngineException.class,
                () -> channel.call("sys.ping", null));
        assertTrue(e.getMessage().contains("未运行"), "关闭后调用应报未运行，实际: " + e.getMessage());
    }

    @Test
    void 启动前调用快速失败() {
        channel = newChannel(5);
        // 不 start 直接 call
        assertThrows(EngineException.class, () -> channel.call("sys.ping", null));
    }
}
