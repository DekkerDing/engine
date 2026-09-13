package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go stdio 通道集成测试（假进程版）—— 任务 2.2 的验收点（D10 合并后随包迁移）：
 * send→Response 往返、超时分支 + 迟到帧自愈、错误帧转 EngineException、
 * 未知方法不致命、优雅关闭/未启动的快败（毒丸分支在 2.5 的杀进程场景覆盖）。
 *
 * <p>【假进程策略】用本 JVM 的 java 可执行文件拉起 {@link FakeGoToolbox}
 * 作为协议对端——不依赖 Go 工具链在场，跨平台一致（Windows/Linux CI 同绿）。
 */
class StdioGoChannelTest {

    private GoStdioChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
    }

    private GoStdioChannel newChannel(long timeoutSeconds) {
        return new GoStdioChannel(fakeProcessSupplier(), timeoutSeconds);
    }

    /** 用当前 JVM 的 java 启动 FakeGoToolbox——协议行为完全可控的假对端。 */
    private GoStdioChannel.ProcessSupplier fakeProcessSupplier() {
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

        GoProtocol.Response ping = channel.send("sys.ping", new HashMap<>());
        assertEquals("pong", ping.result.get("status"), "sys.ping 应返回 pong");

        GoProtocol.Response echo = channel.send("echo.upper", new HashMap<>());
        assertEquals("ECHO", echo.result.get("echo"));
    }

    @Test
    void 超时分支与迟到帧自愈() throws IOException {
        channel = newChannel(1);
        channel.start();
        EngineException e = assertThrows(EngineException.class,
                () -> channel.send("sleep.forever", new HashMap<>()));
        assertTrue(e.getMessage().contains("超时"), "应报超时，实际: " + e.getMessage());
        // 超时后通道仍可用（迟到帧自愈）：紧跟一次正常调用
        GoProtocol.Response ping = channel.send("sys.ping", new HashMap<>());
        assertEquals("pong", ping.result.get("status"), "超时后的下一次调用应恢复正常（迟到帧被丢弃）");
    }

    @Test
    void 未知方法回错误帧且通道存活() throws IOException {
        channel = newChannel(5);
        channel.start();
        EngineException e = assertThrows(EngineException.class,
                () -> channel.send("no.such.method", new HashMap<>()));
        assertTrue(e.getMessage().contains("1001"), "错误帧应带 code=1001，实际: " + e.getMessage());
        assertTrue(channel.isAlive(), "未知方法不应杀死引擎");
        assertEquals("pong", channel.send("sys.ping", new HashMap<>()).result.get("status"));
    }

    @Test
    void 优雅关闭后不可用() throws IOException {
        channel = newChannel(5);
        channel.start();
        channel.close();
        assertFalse(channel.isAlive());
        EngineException e = assertThrows(EngineException.class,
                () -> channel.send("sys.ping", new HashMap<>()));
        assertTrue(e.getMessage().contains("未运行"), "关闭后调用应报未运行，实际: " + e.getMessage());
    }

    @Test
    void 启动前调用快速失败() {
        channel = newChannel(5);
        // 不 start 直接 send
        assertThrows(EngineException.class, () -> channel.send("sys.ping", new HashMap<>()));
    }
}
