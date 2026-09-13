package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.engine.EngineStatus;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.VisionStatusQuery;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CLIP 跨模态编码适配器 —— 与 {@link ChannelEmbeddingProvider} 同通道、异模态并存。
 *
 * <p>【教学注释 · 一个模型，两座塔】chinese-clip 是双塔结构：图像塔与文本塔
 * 各自编码但落在同一个 512 维空间，余弦相似度跨模态可比——"用文字搜照片"
 * 的全部魔法就这一句。落到工程上即两条通道方法：
 * <pre>
 * embedImages(paths)     图像塔：图片字节 → 向量（摄取图片时用）
 * embedClipQuery(texts)  文本塔：查询语句 → 向量（文字搜图时用，与图片同空间）
 * </pre>
 *
 * <p>【为什么 CROSS 模态也会收到文本资源】检索时查询文本由本 provider 编码
 * （进 CLIP 文本塔），所以 embedBatch 必须同时接受两种资源、按 sourceType 分桶
 * 送进对应的塔，再按原顺序组装——调用方（摄取/检索）无需关心塔的存在。
 *
 * <p>【空间键纪律】写出的 {@link Embedding} 一律以 "clip" 为 modelKey
 * （与 Python registry 的 key 一致），这是向量空间的身份证；
 * 真实模型名（OFA-Sys/chinese-clip-vit-base-patch16）只在引擎状态里展示。
 */
@Component
public class ClipEmbeddingProvider implements EmbeddingProvider, VisionStatusQuery {

    private static final Logger log = LoggerFactory.getLogger(ClipEmbeddingProvider.class);

    /** 与 python/core/registry.py 的 clip 槽位 key 一致——空间身份证，不许漂移 */
    public static final String CLIP_KEY = "clip";
    private static final String SOURCE_TYPE_IMAGE = "image";

    /** 图片编码批大小：图片像素体积远大于文本，批要小（CLIP 视觉塔内存大户） */
    private final int batchSize;
    private final PythonChannel channel;

    public ClipEmbeddingProvider(PythonChannel channel,
                                 @Value("${engine.images.vectorize-batch-size:8}") int batchSize) {
        this.channel = channel;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public EmbeddingModality modality() {
        return EmbeddingModality.CROSS;
    }

    @Override
    public String modelKey() {
        return CLIP_KEY;
    }

    @Override
    public int dimension() {
        PythonProtocol.Stats stats;
        try {
            stats = channel.stats();
        } catch (Exception e) {
            return 0;
        }
        return stats.vision == null ? 0 : stats.vision.dimension;
    }

    /**
     * 批量编码（跨模态分桶）：image 资源走图像塔，其余资源走文本塔（查询语句），
     * 结果按输入顺序一一对应返回。
     */
    @Override
    public List<Embedding> embedBatch(List<? extends VectorableResource> resources) {
        List<String> imagePaths = new ArrayList<>();
        List<Integer> imageSlots = new ArrayList<>();
        List<String> queryTexts = new ArrayList<>();
        List<Integer> textSlots = new ArrayList<>();
        for (int i = 0; i < resources.size(); i++) {
            VectorableResource r = resources.get(i);
            if (SOURCE_TYPE_IMAGE.equals(r.sourceType())) {
                imagePaths.add(r.asText()); // ImageAssetResource.asText() = 存储路径
                imageSlots.add(i);
            } else {
                queryTexts.add(r.asText());
                textSlots.add(i);
            }
        }

        Embedding[] slots = new Embedding[resources.size()];
        fillSlots(slots, imageSlots, encodeImages(imagePaths));
        fillSlots(slots, textSlots, encodeQueryTexts(queryTexts));

        List<Embedding> result = new ArrayList<>(resources.size());
        Collections.addAll(result, slots);
        return result;
    }

    private List<Embedding> encodeImages(List<String> paths) {
        if (paths.isEmpty()) {
            return Collections.emptyList();
        }
        List<Embedding> result = new ArrayList<>(paths.size());
        for (int from = 0; from < paths.size(); from += batchSize) {
            List<String> batch = paths.subList(from, Math.min(from + batchSize, paths.size()));
            PythonProtocol.EmbedBatch encoded = callChannel("embedImages",
                    () -> channel.embedImages(batch));
            collect(result, encoded);
            if (encoded.degraded) {
                log.warn("CLIP 处于降级模式（哈希向量），跨模态语义精度受限: {}", encoded.model);
            }
        }
        return result;
    }

    private List<Embedding> encodeQueryTexts(List<String> texts) {
        if (texts.isEmpty()) {
            return Collections.emptyList();
        }
        // 查询语句通常只有一条，但接口按批设计——与文本 provider 的分片纪律一致
        List<Embedding> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            PythonProtocol.EmbedBatch encoded = callChannel("embedClipQuery",
                    () -> channel.embedClipQuery(batch));
            collect(result, encoded);
            if (encoded.degraded) {
                log.warn("CLIP 文本塔处于降级模式（哈希向量），查询语义精度受限: {}", encoded.model);
            }
        }
        return result;
    }

    /** 通道调用统一包装：EngineException 透传，其余包装为下游故障 */
    private PythonProtocol.EmbedBatch callChannel(String op, ChannelCall call) {
        try {
            return call.invoke();
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw EngineException.downstream("CLIP 跨模态编码失败（" + op + "）", e);
        }
    }

    @FunctionalInterface
    private interface ChannelCall {
        PythonProtocol.EmbedBatch invoke();
    }

    /** EmbedBatch → Embedding 列表（空间键固定 "clip"，见类注释的空间键纪律） */
    private static void collect(List<Embedding> sink, PythonProtocol.EmbedBatch encoded) {
        for (float[] vector : encoded.toFloatArrays()) {
            sink.add(new Embedding(vector, CLIP_KEY, encoded.degraded));
        }
    }

    /** 把编码结果按分桶时记录的原位置写回（保证与输入顺序一一对应） */
    private static void fillSlots(Embedding[] slots, List<Integer> positions, List<Embedding> encoded) {
        if (positions.size() != encoded.size()) {
            throw EngineException.internal("CLIP 编码返回数量与输入不符：期望 "
                    + positions.size() + " 实得 " + encoded.size(), null);
        }
        for (int i = 0; i < positions.size(); i++) {
            slots[positions.get(i)] = encoded.get(i);
        }
    }

    // ---------- VisionStatusQuery 实现：/system/health 的 vision 段数据源 ----------

    @Override
    public EngineStatus visionStatus() {
        if (!channel.isAlive()) {
            return EngineStatus.down(channel.channelName(), "通道未运行");
        }
        try {
            PythonProtocol.Stats stats = channel.stats();
            PythonProtocol.Stats.EmbeddingStatus vision = stats.vision;
            if (vision == null) {
                return EngineStatus.down(channel.channelName(), "引擎未返回 vision 状态");
            }
            List<String> loaded = new ArrayList<>();
            if (vision.real_model_loaded) {
                loaded.add(vision.model_key == null ? CLIP_KEY : vision.model_key);
            }
            return new EngineStatus(
                    true,
                    vision.degraded,
                    channel.channelName(),
                    loaded,
                    vision.dimension,
                    vision.degraded ? ("降级运行: " + vision.load_error) : null);
        } catch (Exception e) {
            return EngineStatus.down(channel.channelName(), "vision 状态查询失败: " + e.getMessage());
        }
    }
}
