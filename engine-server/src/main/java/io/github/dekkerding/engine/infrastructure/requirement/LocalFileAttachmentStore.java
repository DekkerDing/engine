package io.github.dekkerding.engine.infrastructure.requirement;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.repository.AttachmentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 附件文件存储 —— data/requirements/{id}/attachments/{uuid}.{ext}。
 *
 * <p>落盘纪律（沿 data/documents/ 惯例）：先写临时文件再原子移动，
 * 避免半截文件被当作完整附件读走。
 */
@Component
public class LocalFileAttachmentStore implements AttachmentStore {

    private final Path baseDir;

    public LocalFileAttachmentStore(
            @Value("${engine.requirements.attachment-dir:data/requirements}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath();
    }

    @Override
    public String store(String requirementId, String fileName, byte[] bytes) {
        try {
            Path dir = baseDir.resolve(requirementId).resolve("attachments");
            Files.createDirectories(dir);
            String extension = extensionOf(fileName);
            Path temp = Files.createTempFile(dir, "upload-", ".part");
            Files.write(temp, bytes);
            Path target = dir.resolve(UUID.randomUUID() + extension);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // 返回相对路径（库里不存绝对路径——数据目录可迁移）
            return baseDir.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw EngineException.internal("附件落盘失败: " + fileName, e);
        }
    }

    @Override
    public byte[] load(String storedPath) {
        try {
            Path path = baseDir.resolve(storedPath).normalize();
            if (!path.startsWith(baseDir)) {
                // 路径穿越防御：stored_path 只能指向 baseDir 内
                throw EngineException.badRequest("非法的附件路径");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw EngineException.internal("附件读取失败: " + storedPath, e);
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot).toLowerCase();
    }
}
