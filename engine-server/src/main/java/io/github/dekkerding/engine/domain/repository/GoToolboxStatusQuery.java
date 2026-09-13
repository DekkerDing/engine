package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.engine.GoToolboxStatus;

/**
 * Go 工具箱状态查询端口（领域层定义，基础设施层实现）。
 *
 * <p>【教学注释 · 端口与适配器】与 {@link EngineStatusQuery} 同款镜像：
 * domain 只声明"我需要什么"（查 Go 引擎健康），infrastructure.go 的
 * GoToolboxProvider 实现它（通道存活 + sys.stats 探活）。健康聚合
 * （SystemQueryService）经本端口拿数据，不知道实现是 stdio 通道还是
 * 将来的别的进程间机制。
 *
 * <p>【缺席语义】{@code engine.go.enabled=false}（默认）时容器里没有实现 bean，
 * 注入方拿到 {@code Optional.empty()} → 健康段输出 N/A（功能在场但未启用）。
 */
public interface GoToolboxStatusQuery {

    /**
     * 返回当前 Go 引擎状态（不抛异常——状态查询必须永远成功；
     * 内部探活失败一律折叠为 ok=false + lastError）。
     */
    GoToolboxStatus status();
}
