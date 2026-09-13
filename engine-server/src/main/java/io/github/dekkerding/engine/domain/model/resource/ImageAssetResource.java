package io.github.dekkerding.engine.domain.model.resource;

/**
 * 图片资源 —— {@link VectorableResource} 的跨模态实现（一图一资源，不分块）。
 *
 * <p>【与文本资源的差异】文档按 ~400 字切块、一块一资源；图片天然是原子资源——
 * CLIP 对整图编码产出一条向量，chunkIndex 恒为 0。展示名（文件名）随向量冗余入库
 * （VectorEntry.text 列），命中列表不用回查文件系统。
 */
public class ImageAssetResource implements VectorableResource {

    private final String imageId;
    /** 原件存储路径（CLIP 编码时 Python 按此路径读像素——见 VectorableResource.asText 约定） */
    private final String storagePath;
    /** 展示名（原始文件名，入库冗余到 VectorEntry.text） */
    private final String fileName;

    public ImageAssetResource(String imageId, String storagePath, String fileName) {
        this.imageId = imageId;
        this.storagePath = storagePath;
        this.fileName = fileName;
    }

    @Override
    public String resourceId() {
        return imageId;
    }

    @Override
    public String sourceType() {
        return "image";
    }

    /** 图片没有文本表示——按接口约定返回存储路径，Python 侧据此读取像素 */
    @Override
    public String asText() {
        return storagePath;
    }

    public String getImageId() { return imageId; }
    public String getStoragePath() { return storagePath; }
    public String getFileName() { return fileName; }
}
