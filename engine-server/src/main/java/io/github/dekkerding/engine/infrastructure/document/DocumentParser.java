package io.github.dekkerding.engine.infrastructure.document;

/**
 * 文档解析器端口 —— "从一种文件格式提取纯文本"的能力抽象。
 *
 * <p>【教学注释 · 策略模式的接缝】每种格式一个实现，CompositeDocumentParser
 * 按扩展名路由。新增格式（比如 HTML/Markdown）= 新增一个实现类，路由自动发现
 * （Spring 注入所有实现），不修改任何既有代码——开闭原则的日常形态。
 */
public interface DocumentParser {

    /** 本实现支持的文件扩展名（小写，不含点），如 "txt" / "pdf" / "docx" */
    String supportedExtension();

    /** 提取纯文本。失败抛 EngineException（由全局异常处理器翻译） */
    String parse(byte[] content, String filename);
}
