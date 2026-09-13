package io.github.dekkerding.engine.domain.model.engine;

import java.util.Collections;
import java.util.List;

/**
 * Go 工具箱状态值对象 —— 描述嵌入的 Go 计算引擎运行时状态（gotoolbox 场景）。
 *
 * <p>【教学注释 · 与 {@link EngineStatus} 的镜像关系】
 * 两者都是"引擎健康"的值对象，但语义重心不同：
 * EngineStatus 描述<b>必需品</b>（Python 向量化引擎挂了，真实模型检索不可用→整体 DEGRADED）；
 * GoToolboxStatus 描述<b>加速器</b>（Go 引擎挂了，检索自动降级本地扫、摄取降级哈希——
 * 服务依然正确可用，只是慢/精度低）。因此健康聚合里 goToolbox 段独立呈现，
 * 不参与整体 status 判定（见 SystemController 的注释）。
 *
 * <p>【enabled 为什么不进模型】"开没开"是装配期事实（engine.go.enabled 默认 false，
 * 通道 bean 根本不在容器里），用 {@code Optional<GoToolboxStatusQuery>} 的在场性表达；
 * 本模型只描述"开了之后的运行时状态"。
 */
public class GoToolboxStatus {

    /** Go 引擎是否可用（通道存活且 sys.stats 能正常响应） */
    private final boolean ok;
    /** Go 引擎自报版本（sys.stats 的 version，如 "0.2.0"；不可用时为 null） */
    private final String version;
    /** 副本内向量条目数（sys.stats 的 vector_count——副本同步是否跟上的直观指标） */
    private final int vectorCount;
    /** 已注册的方法清单（sys.stats 的 tools——方法数是注册表完整性的证据） */
    private final List<String> tools;
    /** 最近一次错误描述（无错误为 null） */
    private final String lastError;

    public GoToolboxStatus(boolean ok, String version, int vectorCount,
                           List<String> tools, String lastError) {
        this.ok = ok;
        this.version = version;
        this.vectorCount = vectorCount;
        this.tools = tools == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(tools);
        this.lastError = lastError;
    }

    /** 引擎完全不可用时的快捷构造（镜像 EngineStatus.down） */
    public static GoToolboxStatus down(String lastError) {
        return new GoToolboxStatus(false, null, 0, Collections.<String>emptyList(), lastError);
    }

    public boolean isOk() { return ok; }
    public String getVersion() { return version; }
    public int getVectorCount() { return vectorCount; }
    public List<String> getTools() { return tools; }
    public String getLastError() { return lastError; }
}
