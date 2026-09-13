package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.image.ImageAnnotation;
import io.github.dekkerding.engine.domain.repository.AnnotationProvider;
import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * 标注适配器 —— Python vlm 槽位（本期 mock）的通道实现。
 *
 * <p>结构沿 {@link ClipEmbeddingProvider}：只做协议 → 领域对象的翻译，
 * 不含业务逻辑。mock 与未来真实 VLM 在 Python 侧同槽位可换，
 * 本类与 {@link AnnotationProvider} 端口零改动。
 */
@Component
public class ChannelAnnotationProvider implements AnnotationProvider {

    private static final Logger log = LoggerFactory.getLogger(ChannelAnnotationProvider.class);

    private final PythonChannel channel;

    public ChannelAnnotationProvider(PythonChannel channel) {
        this.channel = channel;
    }

    @Override
    public ImageAnnotation annotate(String imagePath, String filename, String caption) {
        PythonProtocol.Annotation result;
        try {
            result = channel.annotate(imagePath, filename, caption);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw EngineException.downstream("图片语义拆分失败（annotate）", e);
        }
        if (result == null || result.description == null) {
            throw EngineException.downstream("图片语义拆分返回空结果", null);
        }
        if (result.mocked) {
            // mock 产出的标注是显式声明过的预期行为（不是故障），日志降为 info
            log.info("mock 标注产出（ mocked=true ）: {}", imagePath);
        }
        return new ImageAnnotation(
                result.subject,
                result.description,
                result.tags == null ? new ArrayList<String>() : result.tags,
                result.mocked);
    }
}
