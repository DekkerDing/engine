package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;

import java.util.List;
import java.util.Optional;

/**
 * 需求仓储端口 —— 聚合根的持久化契约（实现：SqliteRequirementRepository）。
 *
 * <p>列表查询返回全量（过滤分页在应用层内存完成——沿文档域 PageResult.of 惯例，
 * 学习规模最诚实的分页）。
 */
public interface RequirementRepository {

    /** 新建（INSERT，id 已由聚合根持有） */
    void insert(RequirementSubmission submission);

    /** 全量更新（编辑保存后同步冗余列与状态） */
    void update(RequirementSubmission submission);

    Optional<RequirementSubmission> findById(String id);

    List<RequirementSubmission> findAll();
}
