package io.github.dekkerding.engine.infrastructure.python;

import io.github.dekkerding.engine.infrastructure.python.protocol.PythonProtocol;

import java.io.IOException;
import java.util.List;

/**
 * Python 通道抽象 —— "双通道聚合"架构的核心接口（用户决策：Py4J 与 stdio 结合）。
 *
 * <p>两个实现（Profile 切换，配置 engine.python.channel 见 application.yml）：
 * <pre>
 * Py4jChannel  @Profile("py4j")   本机环回 TCP :25335，低延迟，默认
 * StdioChannel @Profile("stdio")  子进程 stdin/stdout JSON 行，零 socket，可管道手测
 * </pre>
 *
 * <p>【教学注释 · 为什么这层在 infrastructure 而不是 domain】
 * "通道"是纯技术概念（怎么连上 Python），业务不关心；domain 只看到
 * EmbeddingProvider（编码能力）。技术细节变化（换 gRPC/换端口）止步于此层。
 *
 * <p>【教学注释 · AutoCloseable】实现它即可用 try-with-resources 或
 * Spring 容器关闭钩子统一释放（进程句柄、socket）。
 */
public interface PythonChannel extends AutoCloseable {

    /** 通道名：py4j | stdio（展示在 /system/health） */
    String channelName();

    /** 拉起子进程并建立连接（幂等：已启动则跳过） */
    void start() throws IOException;

    /** 停止子进程、释放资源（幂等） */
    @Override
    void close();

    /** 子进程是否存活 */
    boolean isAlive();

    // ---------- 业务能力（方法签名即与 Python facade 的一一契约） ----------

    /** 批量文本 → 向量（主链路） */
    PythonProtocol.EmbedBatch embedTexts(List<String> texts);

    /** 批量图片（存储路径）→ CLIP 向量（跨模态摄取主链路） */
    PythonProtocol.EmbedBatch embedImages(List<String> paths);

    /** 批量查询文本 → CLIP 文本塔向量（跨模态检索查询侧，输出与图片向量同空间） */
    PythonProtocol.EmbedBatch embedClipQuery(List<String> texts);

    /** 图片语义拆分（本期 mock 实现输出 mocked=true；filename 原始文件名供 mock 派生，
     *  imagePath 存储路径供真实 VLM 读像素；caption 空串表示未提供覆盖描述） */
    PythonProtocol.Annotation annotate(String imagePath, String filename, String caption);

    /** 检索重排：query × 候选文本 逐对打分（分数顺序与候选一致；不可用时抛显式异常） */
    PythonProtocol.RerankScores rerank(String query, List<String> candidates);

    /** 中文分词 */
    PythonProtocol.Tokenize tokenize(String text, String mode);

    /** TF-IDF 关键词提取 */
    PythonProtocol.Keywords keywords(String text, int topK);

    /** 引擎状态快照（模型注册表 + 加载情况） */
    PythonProtocol.Stats stats();
}
