package io.github.dekkerding.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * engine-gateway 启动类（:8090，流量唯一入口）。
 *
 * <p>【整体调用链】（打开浏览器 F12 网络面板对照记忆）
 * <pre>
 * 浏览器 http://localhost:8090/
 *   → gateway 静态资源：返回 React 页面（index.html + JS/CSS）
 * 浏览器 http://localhost:8080/api/documents
 *   → gateway ProxyController：剥掉 /api，转发 http://127.0.0.1:8081/documents
 *   → server DocumentController 处理，响应原路返回
 * server 内部（不在浏览器可见范围）：
 *   → PythonChannel(Py4J/stdio) → Python 子进程（sentence-transformers 模型推理）
 * </pre>
 *
 * <p>【教学注释】网关包名与 server 不同（gateway vs engine）——
 * 两个独立应用各自独立包根，物理上杜绝互相 import 的可能。
 */
/**
 * 【教学注释 · @EnableScheduling】
 * 健康探测（UpstreamHealthService.probe）用 @Scheduled 周期执行——
 * Spring 默认不启用调度器，这个注解是"开关"：它激活的 ThreadPoolTaskScheduler
 * 默认单线程，对本应用（只有一个探测任务）刚好合适，省一个线程。
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
