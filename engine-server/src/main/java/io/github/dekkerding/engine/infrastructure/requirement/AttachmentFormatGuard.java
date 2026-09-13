package io.github.dekkerding.engine.infrastructure.requirement;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 附件格式守卫 —— 扩展名白名单 + 魔数校验 + 20MB 上限（沿 ImageFormatGuard 模式）。
 *
 * <p>【教学注释 · 为什么扩展名不可信】"report.pdf.exe" 改名成 .pdf 毫无成本；
 * 魔数（文件头字节）是内容自带的身份证——PNG 永远以 89 50 4E 47 开头，
 * 骗不了人。两层都要：白名单挡明显违规，魔数挡伪装文件。
 */
@Component
public class AttachmentFormatGuard {

    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<String>(
            Arrays.asList("png", "jpg", "jpeg", "pdf", "docx"));

    /** 校验入口：不合法直接抛 400（EngineException.badRequest） */
    public void check(String fileName, byte[] bytes) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw EngineException.badRequest("附件缺少文件名");
        }
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw EngineException.badRequest(
                    "不支持的附件格式: " + fileName + "（白名单: png/jpg/jpeg/pdf/docx）");
        }
        if (bytes == null || bytes.length == 0) {
            throw EngineException.badRequest("附件内容为空: " + fileName);
        }
        if (bytes.length > MAX_SIZE_BYTES) {
            throw EngineException.badRequest("附件超过大小限制（20MB）: " + fileName);
        }
        if (!magicMatches(extension, bytes)) {
            throw EngineException.badRequest(
                    "附件内容与扩展名不符（魔数校验失败）: " + fileName);
        }
    }

    /** 魔数表：docx 与 zip 家族共享 PK 头（docx 本质是 zip 容器） */
    private boolean magicMatches(String extension, byte[] bytes) {
        if ("png".equals(extension)) {
            return bytes.length > 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4E && bytes[3] == 0x47;
        }
        if ("jpg".equals(extension) || "jpeg".equals(extension)) {
            return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF;
        }
        if ("pdf".equals(extension)) {
            return bytes.length > 4 && bytes[0] == 0x25 && bytes[1] == 0x50
                    && bytes[2] == 0x44 && bytes[3] == 0x46;   // %PDF
        }
        if ("docx".equals(extension)) {
            return bytes.length > 2 && bytes[0] == 0x50 && bytes[1] == 0x4B;   // PK
        }
        return false;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
