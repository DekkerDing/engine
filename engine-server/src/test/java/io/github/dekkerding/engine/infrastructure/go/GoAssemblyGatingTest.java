package io.github.dekkerding.engine.infrastructure.go;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 两级装配门控测试 —— 任务 2.4 的验收点（spec「配置开关与默认零变化」）：
 * <ol>
 *   <li>默认配置：零 Go Bean（launcher/channel/provider 全不装配）——"启动炸弹"已拆除的直接证据</li>
 *   <li>enabled=true + go-toolbox Profile：通道拉起、握手成功、降级 provider 在场</li>
 * </ol>
 *
 * <p>【测试工具】ApplicationContextRunner——Spring Boot 官方的条件装配测试法：
 * 起"最小上下文"（只装三个类，不起整个应用），逐属性组合断言 Bean 在场性。
 *
 * <p>【环境假设】enabled=true 态会走真实定位链拉起子进程（本机命中 go run 开发轨）。
 * 无 Go 工具链的机器上该用例 assume-skip——装配门控逻辑本身不依赖 Go 在场。
 */
class GoAssemblyGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    GoProcessLauncher.class, GoStdioChannel.class, GoToolboxProvider.class));

    @Test
    void 默认配置_零Go装配() {
        runner.run(context -> {
            assertFalse(context.containsBean("goProcessLauncher"),
                    "默认配置不应装配 GoProcessLauncher");
            assertFalse(context.containsBean("goStdioChannel"),
                    "默认配置不应装配 GoStdioChannel（无条件 @Component 时代的启动炸弹已拆除）");
            assertFalse(context.containsBean("goToolboxProvider"),
                    "默认配置不应装配 GoToolboxProvider");
        });
    }

    @Test
    void enabled且goToolboxProfile_通道在场且握手成功() {
        assumeTrue(goToolchainOrBinaryAvailable(), "本机无 Go 工具链/二进制，跳过拉起验证");

        runner.withPropertyValues(
                        "engine.go.enabled=true",
                        "spring.profiles.active=go-toolbox")
                .run(context -> {
                    assertTrue(context.containsBean("goProcessLauncher"), "enabled=true 应装配启动器");
                    GoStdioChannel channel = context.getBean("goStdioChannel", GoStdioChannel.class);
                    assertTrue(channel.isAlive(), "通道应已拉起并完成 sys.ping 握手");
                    assertNotNull(context.getBean("goToolboxProvider", GoToolboxProvider.class),
                            "go-toolbox Profile + enabled 双条件应装配降级 provider");
                    // context 关闭时 runner 自动 close()：@PreDestroy → sys.shutdown 优雅退出
                });
    }

    @Test
    void enabled但无Profile_仅通道不接管向量化() {
        assumeTrue(goToolchainOrBinaryAvailable(), "本机无 Go 工具链/二进制，跳过拉起验证");

        runner.withPropertyValues("engine.go.enabled=true").run(context -> {
            assertTrue(context.containsBean("goStdioChannel"), "enabled=true 应装配通道");
            assertFalse(context.containsBean("goToolboxProvider"),
                    "无 go-toolbox Profile 时不应接管 TEXT 模态（python provider 保持现役）");
        });
    }

    @Test
    void Profile但未enabled_provider不装配() {
        // 通道三件套不启动（enabled=false），只验证 provider 的双条件缺一不可
        runner.withPropertyValues("spring.profiles.active=go-toolbox").run(context -> {
            assertFalse(context.containsBean("goToolboxProvider"),
                    "只开 Profile 不开 enabled：provider 不装配（否则会注入不存在的通道 Bean）");
        });
    }

    /** 定位链能否命中：go 工具链在场或本地构建产物/环境变量在位（与 resolve() 同判定）。 */
    private boolean goToolchainOrBinaryAvailable() {
        try {
            Process p = new ProcessBuilder("go", "version").start();
            boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            String env = System.getenv("ENGINE_GO_BINARY");
            return env != null && new java.io.File(env).isFile();
        }
    }
}
