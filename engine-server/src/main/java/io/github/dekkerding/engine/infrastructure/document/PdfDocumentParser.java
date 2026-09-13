package io.github.dekkerding.engine.infrastructure.document;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * PDF 解析器（PDFBox 2.0.x，JDK 8 兼容线）。
 * 注意：扫描版 PDF（图片型）没有文本层，提取结果为空——这类文件需要 OCR（超出本期范围）。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public String supportedExtension() {
        return "pdf";
    }

    @Override
    public String parse(byte[] content, String filename) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.trim().isEmpty()) {
                throw EngineException.badRequest(
                        "PDF 未提取到文本（可能是扫描件/图片型 PDF，暂不支持 OCR）: " + filename);
            }
            return text;
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw EngineException.badRequest("PDF 解析失败: " + filename + " - " + e.getMessage());
        }
    }
}
