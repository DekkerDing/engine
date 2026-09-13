package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.engine.EngineStatus;

/**
 * CLIP 跨模态引擎状态查询端口 —— /system/health 的 vision 段数据源。
 *
 * <p>【为什么不复用 EngineStatusQuery】文本引擎的状态查询已有三个消费方按单 bean 注入，
 * CLIP 若实现同一接口会造成注入歧义。跨模态引擎是新公民，独立端口各自演化
 * （端口即契约：关心谁的状态就依赖谁的端口）。
 */
public interface VisionStatusQuery {

    /** CLIP 引擎状态快照（模型/维度/降级/错误） */
    EngineStatus visionStatus();
}
