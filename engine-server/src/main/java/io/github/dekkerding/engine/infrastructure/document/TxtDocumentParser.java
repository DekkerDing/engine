package io.github.dekkerding.engine.infrastructure.document;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * txt 解析器。看似最简单，实际藏着中文编码坑：
 * 上传的 txt 可能是 UTF-8 或 GBK（Windows 记事本历史默认）。
 * 策略：先按 UTF-8 解，出现替换字符（解码失败的标志）就回退 GBK。
 */
@Component
public class TxtDocumentParser implements DocumentParser {

    @Override
    public String supportedExtension() {
        return "txt";
    }

    @Override
    public String parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw EngineException.badRequest("文件内容为空: " + filename);
        }
        String utf8 = new String(content, StandardCharsets.UTF_8);
        // U+FFFD 只会在"字节流按 UTF-8 解码失败"时出现——出现即说明源不是 UTF-8
        if (!utf8.contains("�")) {
            return utf8;
        }
        return new String(content, java.nio.charset.Charset.forName("GBK"));
    }
}
