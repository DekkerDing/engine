package io.github.dekkerding.gateway.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 健康探测配置 —— application.yml 中 gateway.health.* 的绑定。
 *
 * <p>【教学注释 · 连续 3 次失败才判 DOWN 的原因】
 * server 重启的窗口期（旧进程退出→新进程就绪）通常只有几秒到十几秒。
 * 探测周期 10 秒的话，窗口内最多失败 1~2 次——阈值取 3 恰好容忍一次重启，
 * 而 server 真挂掉时 30 秒内必然凑满 3 次失败。灵敏与抗抖之间的平衡点。
 */
@Component
@ConfigurationProperties(prefix = "gateway.health")
public class HealthProperties {

    /** 探测周期（毫秒） */
    private long probeIntervalMs = 10000;

    /** 连续失败多少次才判定 DOWN（容忍 server 重启窗口） */
    private int failureThreshold = 3;

    public long getProbeIntervalMs() {
        return probeIntervalMs;
    }

    public void setProbeIntervalMs(long probeIntervalMs) {
        this.probeIntervalMs = probeIntervalMs;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }
}
