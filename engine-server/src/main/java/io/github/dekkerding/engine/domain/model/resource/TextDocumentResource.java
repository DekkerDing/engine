package io.github.dekkerding.engine.domain.model.resource;

/**
 * 文本文档资源 —— {@link VectorableResource} 的本期实现（文档分块向量化）。
 */
public class TextDocumentResource implements VectorableResource {

    private final String documentId;
    private final int chunkIndex;
    private final String text;

    public TextDocumentResource(String documentId, int chunkIndex, String text) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.text = text;
    }

    /** 资源 ID 用 "文档ID:分块序号" 形式，全局唯一且人眼可读 */
    @Override
    public String resourceId() {
        return documentId + ":" + chunkIndex;
    }

    @Override
    public String sourceType() {
        return "text";
    }

    @Override
    public String asText() {
        return text;
    }

    public String getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getText() { return text; }
}
