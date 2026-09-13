package io.github.dekkerding.engine.infrastructure.document;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 组合解析器 —— 按扩展名路由到具体 DocumentParser 实现。
 *
 * <p>【教学注释 · Spring 的集合注入】构造参数声明为 List&lt;DocumentParser&gt;，
 * Spring 会把容器里所有实现都注入进来——新加格式只要加 @Component 实现类，
 * 这里零改动（对比 if-else 硬编码扩展名的写法）。
 */
@Component
public class CompositeDocumentParser {

    private final Map<String, DocumentParser> parsersByExtension = new HashMap<>();

    public CompositeDocumentParser(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            parsersByExtension.put(parser.supportedExtension(), parser);
        }
    }

    /** 解析入口：取扩展名 → 查路由表 → 委派；不支持的格式给明确的错误提示 */
    public String parse(byte[] content, String filename) {
        String extension = extensionOf(filename);
        DocumentParser parser = parsersByExtension.get(extension);
        if (parser == null) {
            throw EngineException.badRequest(
                    "不支持的文件类型 ." + extension + "（支持: " + parsersByExtension.keySet() + "）");
        }
        return parser.parse(content, filename);
    }

    /** 上传受理阶段的格式预校验：不支持的扩展名在这里就拒绝（400），不进摄取管线 */
    public boolean supports(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        return parsersByExtension.containsKey(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw EngineException.badRequest("文件名缺少扩展名: " + filename);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
