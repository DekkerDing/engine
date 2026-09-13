package io.github.dekkerding.engine.interfaces.rest.dto;

import io.github.dekkerding.engine.application.dto.DocumentDetail;
import io.github.dekkerding.engine.domain.model.document.TextChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档详情响应 DTO —— 应用层聚合结果 + 分块文本展开为视图结构。
 */
public class DocumentDetailResponse {

    private final DocumentSummary document;
    private final List<ChunkView> chunks;
    private final String modelKey;
    private final int dimension;
    private final String degradedReason;

    public DocumentDetailResponse(DocumentDetail detail) {
        this.document = new DocumentSummary(detail.getDocument());
        this.chunks = new ArrayList<>();
        for (TextChunk chunk : detail.getChunks()) {
            this.chunks.add(new ChunkView(chunk.getChunkIndex(), chunk.getText()));
        }
        this.modelKey = detail.getModelKey();
        this.dimension = detail.getDimension();
        this.degradedReason = detail.getDegradedReason();
    }

    public DocumentSummary getDocument() { return document; }
    public List<ChunkView> getChunks() { return chunks; }
    public String getModelKey() { return modelKey; }
    public int getDimension() { return dimension; }
    public String getDegradedReason() { return degradedReason; }

    /** 分块视图：序号 + 原文（详情页可直接展示分块明细） */
    public static class ChunkView {
        private final int chunkIndex;
        private final String text;

        public ChunkView(int chunkIndex, String text) {
            this.chunkIndex = chunkIndex;
            this.text = text;
        }

        public int getChunkIndex() { return chunkIndex; }
        public String getText() { return text; }
    }
}
