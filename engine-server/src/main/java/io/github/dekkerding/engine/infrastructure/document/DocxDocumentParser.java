package io.github.dekkerding.engine.infrastructure.document;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * DOCX 解析器（POI 4.1.2）。注意只支持 .docx（Office 2007+ 的 OOXML），
 * 老的 .doc 二进制格式（HWPF）解析质量差且依赖重，本期不支持的格式直接明确报错。
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    @Override
    public String supportedExtension() {
        return "docx";
    }

    @Override
    public String parse(byte[] content, String filename) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            if (text == null || text.trim().isEmpty()) {
                throw EngineException.badRequest("DOCX 未提取到文本: " + filename);
            }
            return text;
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw EngineException.badRequest("DOCX 解析失败: " + filename + " - " + e.getMessage());
        }
    }
}
