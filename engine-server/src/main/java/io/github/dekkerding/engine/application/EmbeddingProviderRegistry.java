package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 向量化 Provider 注册表 —— 按模态路由的唯一入口（design D2）。
 *
 * <p>【教学注释 · 注册制而非 if-else】"什么模态用什么编码器"是一张会增长的表：
 * 文本 MVP 只有 TEXT，图片变更加入 CROSS，未来图搜图再加 IMAGE。把查表逻辑
 * 集中在一个组件里，消费方（摄取/检索/系统状态）只见 {@code require(模态)}，
 * 新模态注册进来零改动——开闭原则的落地不是口号，是"表驱动"。
 *
 * <p>Spring 注入 {@code List<EmbeddingProvider>} 天然完成"注册"动作：
 * 每个实现类标 {@code @Component} 即入表，本类只做模态键索引与冲突检测。
 */
@Component
public class EmbeddingProviderRegistry {

    private final Map<EmbeddingModality, EmbeddingProvider> byModality = new EnumMap<>(EmbeddingModality.class);

    public EmbeddingProviderRegistry(List<EmbeddingProvider> providers) {
        for (EmbeddingProvider provider : providers) {
            EmbeddingProvider existing = byModality.put(provider.modality(), provider);
            if (existing != null) {
                throw new IllegalStateException("模态 " + provider.modality() + " 注册了多个 provider: "
                        + existing.getClass().getSimpleName() + " / " + provider.getClass().getSimpleName());
            }
        }
    }

    /** 按模态取 provider；缺失直接失败（启动期装配问题不该拖到运行期才爆） */
    public EmbeddingProvider require(EmbeddingModality modality) {
        EmbeddingProvider provider = byModality.get(modality);
        if (provider == null) {
            throw new IllegalStateException("容器中缺少 " + modality + " 模态的 EmbeddingProvider");
        }
        return provider;
    }

    /** 按模态取 provider；缺失返回 null（可选能力：CLIP 未装配时系统仍可纯文本运行） */
    public EmbeddingProvider find(EmbeddingModality modality) {
        return byModality.get(modality);
    }
}
