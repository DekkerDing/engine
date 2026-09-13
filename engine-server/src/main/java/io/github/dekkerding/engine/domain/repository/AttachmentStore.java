package io.github.dekkerding.engine.domain.repository;

/**
 * 附件文件存储端口 —— 二进制本体的落盘/读取（实现：LocalFileAttachmentStore）。
 *
 * <p>【教学注释 · 元数据与本体分仓储】行进 SQLite、文件进文件系统：
 * 各用各擅长的存储（关系查询 vs 大二进制），这是经典的 metadata-blob 分离。
 */
public interface AttachmentStore {

    /** 落盘，返回存储相对路径（写入 attachment.stored_path） */
    String store(String requirementId, String fileName, byte[] bytes);

    /** 按存储路径读取文件本体 */
    byte[] load(String storedPath);
}
