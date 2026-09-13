package io.github.dekkerding.engine.domain.model.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 图片资源值对象测试 —— 任务 2.1 的验收点：
 * VectorableResource 三方法契约（ID 唯一性载体 / 模态标签 / 路径即文本表示）。
 */
class ImageAssetResourceTest {

    @Test
    void 三方法契约_sourceType恒为image() {
        ImageAssetResource resource = new ImageAssetResource(
                "img-001", "data/images/img-001.jpg", "红塔.jpg");

        assertEquals("img-001", resource.resourceId());
        assertEquals("image", resource.sourceType());
        assertEquals("data/images/img-001.jpg", resource.asText());
    }

    @Test
    void 展示名与存储路径分离() {
        ImageAssetResource resource = new ImageAssetResource(
                "img-002", "data/images/img-002.png", "小花.png");

        // asText 是给编码器看的（路径）；文件名是给人看的（入库冗余展示）
        assertEquals("data/images/img-002.png", resource.asText());
        assertEquals("小花.png", resource.getFileName());
        assertEquals("img-002", resource.getImageId());
    }
}
