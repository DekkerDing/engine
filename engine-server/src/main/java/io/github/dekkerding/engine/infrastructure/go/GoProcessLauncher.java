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
import java.util.concurrent.TimeUnit;

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

    /** 定位 Go 二进制或入口源码，返回可启动的 ProcessBuilder */
    public ProcessBuilder resolve() throws IOException {
        if (configuredBinary != null && !configuredBinary.isEmpty()) {
            File bin = new File(configuredBinary);
            if (bin.isFile()) {
                return new ProcessBuilder(bin.getAbsolutePath());
            }
            throw new IOException("configured Go binary not found: " + bin.getAbsolutePath());
        }
        String env = System.getenv("ENGINE_GO_BINARY");
        if (env != null && !env.isEmpty()) {
            File bin = new File(env);
            if (bin.isFile()) {
                return new ProcessBuilder(bin.getAbsolutePath());
            }
        }
        File toolExe = new File("golang/toolbox.exe");
        if (toolExe.isFile()) {
            return new ProcessBuilder(toolExe.getAbsolutePath());
        }
        File mainGo = new File("golang/cmd/toolbox/main.go");
        if (mainGo.isFile()) {
            ProcessBuilder pb = new ProcessBuilder(goCommand, "run", ".");
            pb.directory(mainGo.getParentFile());
            return pb;
        }
        throw new IOException("Go engine entry not found.");
    }

    public Process launch() throws IOException {
        ProcessBuilder pb = resolve();
        pb.directory(pb.directory() != null ? pb.directory() : new File("."));
        Process process = pb.start();
        log.info("Go engine started (pid={})", pid(process));
        // stderr → log (stdout is protocol line, do NOT touch)
        bridgeToLog(process.getErrorStream(), "[go-err] ");
        return process;
    }

    private void bridgeToLog(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("{}{}", prefix, line);
                }
            } catch (Exception ignored) {}
        }, "toolbox-stderr");
        t.setDaemon(true);
        t.start();
    }

    private long pid(Process process) {
        try {
            java.lang.reflect.Field f = process.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            return (Long) f.get(process);
        } catch (Exception e) {
            return -1;
        }
    }

    public void stop(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}