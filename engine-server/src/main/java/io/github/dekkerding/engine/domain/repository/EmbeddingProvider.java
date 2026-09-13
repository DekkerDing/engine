package io.github.dekkerding.engine.domain.repository;

import io.github.dekkerding.engine.domain.model.resource.VectorableResource;
import io.github.dekkerding.engine.domain.model.vector.Embedding;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;

import java.util.List;

/**
 * 向量化端口（按模态可插拔）—— 领域层最核心的扩展点之一。
 *
 * <p>【依赖倒置落地】应用层/领域服务只依赖本接口；
 * infrastructure 层的 ChannelEmbeddingProvider 实现它（内部走 PythonChannel → Python 子进程）。
 * 测试时可以注入一个假实现（返回固定向量），领域逻辑完全不碰 Python。
 *
 * <p>【图片扩展路径】后期新增 ClipEmbeddingProvider（modality=CROSS），
 * 同一批 VectorEntry 表里 text/image 混存，检索接口不变——跨模态检索自动成立。
 */
public interface EmbeddingProvider {

    /** 本 provider 负责的模态（一个 provider 一个模态，注册制而非 if-else） */
    EmbeddingModality modality();

    /** 模型键（对应 Python 端 registry：text-embedding / clip ...） */
    String modelKey();

    /** 输出维度（MiniLM=384；CLIP 通常 512——不同 provider 维度可以不同） */
    int dimension();

    /** 批量编码：输入资源列表，输出与输入顺序一一对应的向量列表 */
    List<Embedding> embedBatch(List<? extends VectorableResource> resources);
}
