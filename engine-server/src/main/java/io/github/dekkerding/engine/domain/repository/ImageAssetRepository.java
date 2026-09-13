package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.image.ImageAsset;

import java.util.List;
import java.util.Optional;

/**
 * 图片资产仓储端口 —— 领域层定义"我需要什么存取能力"，由 infrastructure 的
 * SqliteImageAssetRepository 实现（依赖倒置）。
 *
 * <p>接口只放应用层真实用到的方法（对照 DocumentRepository 的克制）：
 * 图片没有分块，天然没有 saveChunks。
 */
public interface ImageAssetRepository {

    void save(ImageAsset imageAsset);

    void update(ImageAsset imageAsset);

    Optional<ImageAsset> findById(String id);

    /** 按上传时间倒序列出全部图片 */
    List<ImageAsset> findAll();

    void deleteById(String id);
}
