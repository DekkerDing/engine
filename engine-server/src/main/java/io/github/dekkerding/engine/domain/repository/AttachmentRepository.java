package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.requirement.Attachment;

import java.util.List;
import java.util.Optional;

/**
 * 附件仓储端口 —— 元数据行；文件本体走 {@link AttachmentStore}。
 */
public interface AttachmentRepository {

    /** 新建附件行，返回带数据库 id 的实体 */
    Attachment insert(Attachment attachment);

    List<Attachment> findByRequirement(String requirementId);

    Optional<Attachment> findById(long id);

    Optional<Attachment> findById(String requirementId, long id);
}
