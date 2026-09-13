package io.github.dekkerding.engine.domain.model.engine;

import java.util.Collections;
import java.util.List;

/**
 * 引擎状态值对象 —— 描述 Python 向量化引擎的运行时状态。
 *
 * <p>【教学注释 · 值对象 vs 实体】
 * EngineStatus 没有身份 ID，两个内容相同的状态完全等价——典型的值对象（Value Object）。
 * 与之相对，Document 有唯一 ID 的"实体"（Entity），即便内容改了还是同一个文档。
 *
 * <p>【图片扩展预留】loadedModels 未来会包含 "image-embedding" / "clip" 条目，
 * 前端 System 页无需改动就能展示新模态的加载状态。
 */
public class EngineStatus {

    /** 引擎是否可用（能正常响应调用） */
    private final boolean ok;
    /** 是否降级：true = 真实模型不可用，正在用确定性哈希向量兜底（精度降低但不阻塞流程） */
    private final boolean degraded;
    /** 当前使用的通道：py4j | stdio */
    private final String channel;
    /** 已加载的模型注册表条目（text-embedding / image-embedding / clip...） */
    private final List<String> loadedModels;
    /** 向量维度（MiniLM=384；换模型后自动变化） */
    private final int dimension;
    /** 最近一次错误描述（无错误为 null） */
    private final String lastError;

    public EngineStatus(boolean ok, boolean degraded, String channel,
                        List<String> loadedModels, int dimension, String lastError) {
        this.ok = ok;
        this.degraded = degraded;
        this.channel = channel;
        this.loadedModels = loadedModels == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(loadedModels);
        this.dimension = dimension;
        this.lastError = lastError;
    }

    /** 引擎完全不可用时的快捷构造 */
    public static EngineStatus down(String channel, String lastError) {
        return new EngineStatus(false, false, channel,
                Collections.<String>emptyList(), 0, lastError);
    }

    public boolean isOk() { return ok; }
    public boolean isDegraded() { return degraded; }
    public String getChannel() { return channel; }
    public List<String> getLoadedModels() { return loadedModels; }
    public int getDimension() { return dimension; }
    public String getLastError() { return lastError; }
}
