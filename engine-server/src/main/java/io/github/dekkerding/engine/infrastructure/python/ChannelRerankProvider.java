package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.repository.RerankProvider;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重排适配器 —— Python reranker 槽位（bge-reranker-base）的通道实现。
 *
 * <p>【错误语义不翻译成降级】通道/模型不可用时异常直接上抛
 * （EngineException.downstream），由检索服务捕获后走"reranked=false 直通"
 * 降级——见 {@link RerankProvider} 的纪律注释：重排不存在"哈希兜底"，
 * 伪造分数比没有重排更糟。
 *
 * <p>【让位语义】go-toolbox Profile 激活时缺席——检索服务对 RerankProvider
 * 本就是 Optional 依赖，缺席即"直通不重排"，与上述降级纪律同一条路。
 */
@Component
@Profile("!go-toolbox")
public class ChannelRerankProvider implements RerankProvider {

    private static final Logger log = LoggerFactory.getLogger(ChannelRerankProvider.class);

    private final PythonChannel channel;

    public ChannelRerankProvider(PythonChannel channel) {
        this.channel = channel;
    }

    @Override
    public float[] rerank(String query, List<String> candidates) {
        PythonProtocol.RerankScores scores;
        try {
            scores = channel.rerank(query, candidates);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw EngineException.downstream("重排调用失败（rerank）", e);
        }
        if (scores == null || scores.scores.size() != candidates.size()) {
            throw EngineException.downstream("重排返回分数数量与候选不符：期望 "
                    + candidates.size() + " 实得 " + (scores == null ? 0 : scores.scores.size()), null);
        }
        return scores.toFloatArray();
    }
}
