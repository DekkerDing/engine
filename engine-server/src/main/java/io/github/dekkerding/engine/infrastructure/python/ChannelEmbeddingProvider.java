package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.engine.EngineStatus;
import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.EngineStatusQuery;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道 → 领域端口的适配器：把 PythonChannel（技术视图）包装成
 * EmbeddingProvider + EngineStatusQuery（业务视图）。
 *
 * <p>【教学注释 · 适配器模式的收益】domain/application 层从此刻起
 * 再也不出现 py4j/stdio/process 等技术词汇；将来换 gRPC 或 HTTP 通道，
 * 只换 infrastructure 内部实现，这一层及以上全部不动。
 *
 * <p>【图片扩展预留】后期新增 ClipEmbeddingProvider 同样实现 EmbeddingProvider
 * （modality=CROSS，modelKey="clip"），与本类并存注册——按模态查找 provider 即可。
 */
@Component
public class ChannelEmbeddingProvider implements EmbeddingProvider, EngineStatusQuery {

    private static final Logger log = LoggerFactory.getLogger(ChannelEmbeddingProvider.class);

    /** 向量化的批大小：一次 Python 调用带多少条文本（模型批推理远快于逐条） */
    private final int batchSize;
    /** 模型键：与 engine.python.model-key 一致（launcher 已同步注入 Python 子进程环境） */
    private final String modelKey;
    private final PythonChannel channel;

    public ChannelEmbeddingProvider(PythonChannel channel,
                                    @Value("${engine.documents.vectorize-batch-size:16}") int batchSize,
                                    @Value("${engine.python.model-key:text-embedding-zh}") String modelKey) {
        this.channel = channel;
        this.batchSize = Math.max(1, batchSize);
        this.modelKey = modelKey;
    }

    @Override
    public io.github.dekkerding.engine.domain.model.vector.EmbeddingModality modality() {
        return io.github.dekkerding.engine.domain.model.vector.EmbeddingModality.TEXT;
    }

    @Override
    public String modelKey() {
        return modelKey;
    }

    @Override
    public int dimension() {
        // 动态取自通道（模型配置变化时无需改代码）
        PythonProtocol.Stats stats;
        try {
            stats = channel.stats();
        } catch (Exception e) {
            return 0;
        }
        return stats.embedding == null ? 0 : stats.embedding.dimension;
    }

    @Override
    public List<io.github.dekkerding.engine.domain.model.vector.Embedding> embedBatch(
            List<? extends VectorableResource> resources) {
        List<String> texts = new ArrayList<>(resources.size());
        for (VectorableResource r : resources) {
            texts.add(r.asText());
        }

        // 分片：批大小切片逐批调用，避免超大文档一次喂爆内存/超时
        List<io.github.dekkerding.engine.domain.model.vector.Embedding> result = new ArrayList<>(resources.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            PythonProtocol.EmbedBatch encoded = embedShard(batch);
            float[][] vectors = encoded.toFloatArrays();
            for (float[] vector : vectors) {
                // 【空间键语义】这里必须写配置键（与 modelKey() 一致），不能写 encoded.model
                // （Python 的 model_name 如 "BAAI/bge-small-zh-v1.5"）：检索侧按 provider.modelKey()
                // 过滤空间，摄取侧写入的键若不一致，空间闸门在真实数据上永不匹配。
                // 模型名只作为引擎状态展示（见 status() 的 model_name 段），不参与空间身份
                result.add(new io.github.dekkerding.engine.domain.model.vector.Embedding(
                        vector, modelKey, encoded.degraded));
            }
            if (encoded.degraded) {
                log.warn("embedding 处于降级模式（哈希向量），语义精度受限: {}", encoded.model);
            }
        }
        return result;
    }

    private PythonProtocol.EmbedBatch embedShard(List<String> batch) {
        try {
            return channel.embedTexts(batch);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw EngineException.downstream("文本向量化失败", e);
        }
    }

    // ---------- EngineStatusQuery 实现：/system/health 的数据源 ----------

    @Override
    public EngineStatus status() {
        if (!channel.isAlive()) {
            return EngineStatus.down(channel.channelName(), "通道未运行");
        }
        try {
            PythonProtocol.Stats stats = channel.stats();
            PythonProtocol.Stats.EmbeddingStatus emb = stats.embedding;
            if (emb == null) {
                return EngineStatus.down(channel.channelName(), "引擎未返回状态");
            }
            List<String> loaded = new ArrayList<>();
            if (emb.real_model_loaded) {
                loaded.add(emb.model_key == null ? "text-embedding" : emb.model_key);
            }
            return new EngineStatus(
                    true,
                    emb.degraded,
                    channel.channelName(),
                    loaded,
                    emb.dimension,
                    emb.degraded ? ("降级运行: " + emb.load_error) : null);
        } catch (Exception e) {
            return EngineStatus.down(channel.channelName(), "状态查询失败: " + e.getMessage());
        }
    }
}
