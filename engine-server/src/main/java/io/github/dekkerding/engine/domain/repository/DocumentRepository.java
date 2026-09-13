package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.TextChunk;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储端口 —— 领域层定义"我需要什么存取能力"，由 infrastructure 的
 * SqliteDocumentRepository 实现（依赖倒置）。
 *
 * <p>【教学注释 · 为什么方法这么少】仓储接口只放"应用层真实用到"的方法，
 * 不照着 SQL 能力设计（没有 updateFilename 就是不需要）——接口即契约，越小越稳。
 */
public interface DocumentRepository {

    void save(Document document);

    void update(Document document);

    Optional<Document> findById(String id);

    /** 按上传时间倒序列出全部文档 */
    List<Document> findAll();

    void deleteById(String id);

    // ---------- 分块存取 ----------

    void saveChunks(String documentId, List<TextChunk> chunks);

    List<TextChunk> findChunks(String documentId);

    /** 删除文档的分块（文档删除时级联） */
    void deleteChunks(String documentId);
}
