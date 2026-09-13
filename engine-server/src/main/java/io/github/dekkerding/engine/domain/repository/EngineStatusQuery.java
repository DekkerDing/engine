package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.engine.EngineStatus;

/**
 * 引擎状态查询端口（领域层定义，基础设施层实现）。
 *
 * <p>【教学注释 · 端口与适配器（Hexagonal Architecture）】
 * domain 层只描述"我需要什么能力"（查询引擎状态），
 * 不关心实现是 Py4J 通道、stdio 通道还是将来的 gRPC——
 * infrastructure 层负责"怎么做到"。这就是依赖倒置：
 *   传统：业务代码 → 直接 new 技术细节（改技术就要改业务）
 *   倒置：业务定义接口 ← 技术实现接口（换技术不动业务）
 *
 * <p>P1 阶段 infrastructure.python 的两个通道实现（Py4jChannel / StdioChannel）
 * 都会实现本接口；本接口也决定了 /system/health 聚合数据的来源。
 */
public interface EngineStatusQuery {

    /** 返回当前引擎状态（不触发重启、不抛异常——状态查询必须永远成功） */
    EngineStatus status();
}
