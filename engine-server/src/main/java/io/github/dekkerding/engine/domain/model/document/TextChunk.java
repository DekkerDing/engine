package io.github.dekkerding.engine.domain.model.document;

/**
 * 文本分块值对象 —— 文档切出来的一个语义段。
 *
 * <p>【为什么分块】embedding 模型有输入上限（MiniLM 约 512 token ≈ 400 汉字），
 * 整篇文档直接编码会被静默截断（蓝本 800 字符分块就踩了这个坑：后半篇等于没编码）。
 * 分块 + 检索时只取最相关的块，既绕开长度限制又提高检索精度——这是 RAG 的基础操作。
 */
public class TextChunk {

    private final String documentId;
    private final int chunkIndex;
    private final String text;
    private final int charCount;

    public TextChunk(String documentId, int chunkIndex, String text) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.charCount = text == null ? 0 : text.length();
    }

    public String getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getText() { return text; }
    public int getCharCount() { return charCount; }
}
