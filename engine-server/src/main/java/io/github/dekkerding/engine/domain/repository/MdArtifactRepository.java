package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;

import java.util.List;
import java.util.Optional;

/**
 * md 工件仓储端口 —— 版本是 (requirementId, target) 内的单调递增整数，
 * 由仓储统一发号（避免应用层先读后写的竞态——单写者纪律）。
 */
public interface MdArtifactRepository {

    /** 下一个版本号（该需求 × 该目标的 max(version)+1，从 1 起） */
    int nextVersion(String requirementId, RenderTarget target);

    /** 新建工件行（version 已定），返回带数据库 id 的实体 */
    MdArtifact insert(MdArtifact artifact);

    /** 更新可变域：revised/files/is_stale */
    void update(MdArtifact artifact);

    /** 该需求的全部工件（按 target+version 排序，最新在后） */
    List<MdArtifact> findByRequirement(String requirementId);

    Optional<MdArtifact> find(String requirementId, RenderTarget target, int version);

    /** 该需求该目标的最新版本工件（导出入口） */
    Optional<MdArtifact> findLatest(String requirementId, RenderTarget target);
}
