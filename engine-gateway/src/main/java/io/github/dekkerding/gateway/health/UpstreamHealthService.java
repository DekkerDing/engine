package io.github.dekkerding.gateway.health;

import io.github.dekkerding.gateway.proxy.UpstreamProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上游健康探测服务 —— 网关三件事之三的数据源。
 *
 * <p>【职责】周期探测 engine-server，维护两份缓存供 /api/system/health 聚合：
 * <ol>
 *   <li><b>存活判定</b>：连续 {@code failureThreshold} 次失败才 DOWN（容忍重启窗口），
 *       一次成功立即恢复 UP——"转坏要慢、转好要快"，避免边界抖动导致状态翻转。</li>
 *   <li><b>引擎段透传</b>：探测成功时顺手缓存响应里的 engine / documents 段
 *       （server 的 /system/health 一次返回存活+引擎+统计三样），前端仪表盘
 *       直接消费网关聚合结果，无需再跨进程直连 server。</li>
 * </ol>
 *
 * <p>【教学注释 · 为什么探测 /system/health 而不是 /actuator/health】
 * 一次请求同时拿到三层数据（存活、Python 引擎、文档统计），请求量减半；
 * 且该接口的"存活"语义与业务一致（数据库打不开时它也会失败，比纯进程存活更真实）。
 *
 * <p>【教学注释 · volatile 与 AtomicInteger】
 * @Scheduled 探测线程写、HTTP 请求线程（HealthController）读——典型的跨线程共享。
 * 简单状态用 volatile（保证可见性）；失败计数用 AtomicInteger（读改写三步必须原子）。
 */
@Service
public class UpstreamHealthService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamHealthService.class);

    private final OkHttpClient httpClient;
    private final OkHttpClient probeClient;   // 探测专用派生客户端：短 call 超时，探测绝不拖 60 秒
    private final String healthUrl;
    private final int failureThreshold;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 连续失败计数：>= failureThreshold 时判 DOWN（Atomic 保证"读-改-写"原子） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** server 存活标志（volatile：探测线程写、请求线程读） */
    private volatile boolean serverUp = false;

    /** 最近一次成功探测缓存的 engine 段（Python 引擎状态，原样透传给前端） */
    private volatile JsonNode engineSection;

    /** 最近一次成功探测缓存的 documents 段（文档统计） */
    private volatile JsonNode documentsSection;

    /** 最近一次失败原因（DOWN 时前端展示用） */
    private volatile String lastError = "尚未完成首次探测";

    /** 最近一次探测成功时间（ISO-8601；null 表示启动后从未成功） */
    private volatile Instant lastSuccessAt;

    public UpstreamHealthService(OkHttpClient httpClient,
                                 UpstreamProperties upstreamProperties,
                                 HealthProperties healthProperties) {
        this.httpClient = httpClient;
        this.healthUrl = upstreamProperties.getBaseUrl() + "/system/health";
        this.failureThreshold = healthProperties.getFailureThreshold();
        // newBuilder() 派生新客户端：共享连接池，只覆盖超时——探测是轻量调用，
        // 不应沿用代理的 60s 读超时（server 假死时探测会排队堆积）
        this.probeClient = httpClient.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 周期探测入口。fixedDelay = 上一次执行<b>结束</b>到下一次<b>开始</b>的间隔：
     * 探测本身耗时不会叠加进周期（fixedRate 会），周期语义更稳定。
     * 启动后立即执行第一次（无需等一个周期才有数据）。
     */
    @Scheduled(fixedDelayString = "${gateway.health.probe-interval-ms:10000}")
    public void probe() {
        Request request = new Request.Builder().url(healthUrl).get().build();
        try (Response response = probeClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                recordFailure("HTTP " + response.code());
                return;
            }
            JsonNode envelope = objectMapper.readTree(response.body().byteStream());
            // server 统一信封：code=0 才是健康（HTTP 200 但业务异常同样视为不健康）
            if (envelope.path("code").asInt(-1) != 0 || !envelope.has("data")) {
                recordFailure("健康接口返回异常信封");
                return;
            }
            JsonNode data = envelope.get("data");
            this.engineSection = data.get("engine");
            this.documentsSection = data.get("documents");
            this.lastSuccessAt = Instant.now();
            // 转好要快：一次成功立即 UP + 计数清零
            this.consecutiveFailures.set(0);
            if (!serverUp) {
                serverUp = true;
                log.info("上游 engine-server 探测恢复: UP");
            }
        } catch (IOException e) {
            recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 记一次失败：只有连续凑满阈值才转 DOWN（转坏要慢，容忍重启窗口） */
    private void recordFailure(String reason) {
        this.lastError = reason;
        int failures = consecutiveFailures.incrementAndGet();
        if (serverUp && failures >= failureThreshold) {
            serverUp = false;
            log.warn("上游 engine-server 连续 {} 次探测失败，标记 DOWN: {}", failures, reason);
        } else if (failures < failureThreshold) {
            log.info("上游探测失败（{}/{}）: {}", failures, failureThreshold, reason);
        }
    }

    /** server 是否存活（聚合健康的数据源） */
    public boolean isServerUp() {
        return serverUp;
    }

    /** 最近缓存的 Python 引擎段（server DOWN 期间保留最后一次成功值，前端可见"最后已知状态"） */
    public JsonNode getEngineSection() {
        return engineSection;
    }

    /** 最近缓存的文档统计段 */
    public JsonNode getDocumentsSection() {
        return documentsSection;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }
}
