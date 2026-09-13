package io.github.dekkerding.engine.infrastructure.golang;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 假 Go 引擎 —— 用 JVM 子进程模拟 toolbox 二进制的协议行为（任务 2.2/2.5 的"假进程"）。
 *
 * <p>【为什么要假进程】通道层的往返/超时/毒丸语义需要真实的多进程 IO 来验证，
 * 但单测不该依赖 Go 工具链在场——用 java 起一个同协议的对端，行为完全可控：
 * <ul>
 *   <li>sys.ping → 立即回 pong</li>
 *   <li>sleep.forever → 永不回（构造超时分支）</li>
 *   <li>die.now → 直接 System.exit（构造进程死亡/毒丸分支）</li>
 *   <li>echo.* → 回显 params（构造通用往返）</li>
 * </ul>
 * 真实 Go 二进制的端到端验证归 channelIT 式集成轨（与 python 通道同一分离策略）。
 */
public class FakeGoToolbox {

    public static void main(String[] args) throws Exception {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                // 极简解析：抽出 id 与 method 两个字段足够（协议两侧的 JSON 库各司其职）
                long id = extractLong(line, "\"id\":");
                String method = extractString(line, "\"method\":\"");
                if (method == null) {
                    respond(out, "{\"id\":" + id + ",\"error\":{\"code\":1002,\"message\":\"坏帧\"}}");
                    continue;
                }
                switch (method) {
                    case "sys.ping":
                        respond(out, "{\"id\":" + id + ",\"result\":{\"status\":\"pong\"}}");
                        break;
                    case "echo.upper":
                        respond(out, "{\"id\":" + id + ",\"result\":\"ECHO\"}");
                        break;
                    case "sleep.forever":
                        // 超时分支：调用方 1s 超时放弃后，这里在 2s 补发"迟到帧"
                        // ——既构造超时，又让后续调用必须丢弃这帧才能拿到自己的响应
                        Thread.sleep(2_000);
                        respond(out, "{\"id\":" + id + ",\"result\":\"late\"}");
                        break;
                    case "die.now":
                        respond(out, "{\"id\":" + id + ",\"result\":\"dying\"}");
                        out.flush();
                        System.exit(0);
                        break;
                    case "sys.shutdown":
                        respond(out, "{\"id\":" + id + ",\"result\":{\"status\":\"bye\"}}");
                        return;
                    default:
                        respond(out, "{\"id\":" + id
                                + ",\"error\":{\"code\":1001,\"message\":\"方法不存在: " + method + "\"}}");
                }
            }
        }
    }

    private static void respond(BufferedWriter out, String frame) throws Exception {
        out.write(frame);
        out.newLine();
        out.flush();
    }

    private static long extractLong(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return 0;
        }
        int start = i + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static String extractString(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int end = json.indexOf('"', i + key.length());
        return json.substring(i + key.length(), end);
    }
}
