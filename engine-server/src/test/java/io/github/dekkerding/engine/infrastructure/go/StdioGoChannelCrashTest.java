package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go 通道崩溃语义测试 —— 任务 2.5 的验收点（spec「进程生命周期与故障语义」）：
 * 子进程被外杀/自杀后，在途与后续调用快速失败（毒丸 + 写失败双路径），
 * 绝不无限阻塞；长超时配置下仍秒级失败——证明快败来自毒丸而非超时兜底。
 *
 * <p>【为何长超时】通道配 30s 超时：若崩溃后调用真的等满超时才失败，
 * 本测试会明显变慢且断言 dt &lt; 10s 失败——超时上限与崩溃快败的机制分离由此可证。
 */
class StdioGoChannelCrashTest {

    private GoStdioChannel channel;
    private final AtomicReference<Process> captured = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
    }

    private GoStdioChannel newCrashChannel() {
        GoStdioChannel.ProcessSupplier supplier = () -> {
            String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin"
                    + java.io.File.separator + "java";
            String classpath = System.getProperty("java.class.path");
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classpath,
                    FakeGoToolbox.class.getName());
            Process p = pb.start();
            captured.set(p); // 捕获进程句柄，供测试外杀
            return p;
        };
        return new GoStdioChannel(supplier, 30);
    }

    @Test
    void 外杀后在途与后续调用快速失败() throws Exception {
        channel = newCrashChannel();
        channel.start();
        assertEquals("pong", channel.send("sys.ping", new HashMap<>()).result.get("status"));

        Process p = captured.get();
        p.destroyForcibly();
        assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "进程应在 5s 内死透");

        assertFalse(channel.isAlive(), "进程死后通道 isAlive 应为 false");
        long t0 = System.currentTimeMillis();
        EngineException first = assertThrows(EngineException.class,
                () -> channel.send("sys.ping", new HashMap<>()));
        long elapsed = System.currentTimeMillis() - t0;
        // 写失败（Windows 管道立断）与毒丸（Linux 缓冲先成）都是合法快败路径
        assertTrue(first.getMessage().contains("退出") || first.getMessage().contains("写入失败"),
                "应报引擎退出语义错误，实际: " + first.getMessage());
        assertTrue(elapsed < 10_000, "快败应秒级返回（实测 " + elapsed + "ms），不得等满 30s 超时");

        EngineException second = assertThrows(EngineException.class,
                () -> channel.send("echo.upper", new HashMap<>()));
        assertTrue(second.getMessage().contains("退出") || second.getMessage().contains("写入失败")
                        || second.getMessage().contains("未运行"),
                "后续调用应持续快败，实际: " + second.getMessage());
    }

    @Test
    void 引擎自杀后下一次调用快败() throws Exception {
        channel = newCrashChannel();
        channel.start();

        // die.now：假引擎先回响应再 System.exit——本次调用正常完成，死亡发生在帧后
        GoProtocol.Response dying = channel.send("die.now", new HashMap<>());
        assertEquals("dying", dying.result.get("status"));

        Process p = captured.get();
        assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "自杀进程应在 5s 内退出");

        long t0 = System.currentTimeMillis();
        EngineException e = assertThrows(EngineException.class,
                () -> channel.send("sys.ping", new HashMap<>()));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(e.getMessage().contains("退出") || e.getMessage().contains("写入失败"),
                "应报引擎退出语义错误，实际: " + e.getMessage());
        assertTrue(elapsed < 10_000, "快败应秒级返回（实测 " + elapsed + "ms）");
    }

    @Test
    void 崩溃后关闭不抛出() throws Exception {
        // @PreDestroy 的防护性验证：进程已死时 close() 仍须静默成功（不发僵尸帧、不阻塞）
        channel = newCrashChannel();
        channel.start();
        captured.get().destroyForcibly();
        assertTrue(captured.get().waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        channel.close(); // 不抛即过
        assertFalse(channel.isAlive());
    }
}
