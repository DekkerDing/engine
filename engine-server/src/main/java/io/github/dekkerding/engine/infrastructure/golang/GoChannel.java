package io.github.dekkerding.engine.infrastructure.golang;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Go 引擎通道端口 —— Java 侧调用 Go 工具方法的唯一门面协议（镜像 {@code PythonChannel}）。
 *
 * <p>【通用性设计】通道只暴露一个通用 {@link #call}：方法名 + params JSON →
 * result JSON。具体工具方法（hashing.* / vector.* / text.*）的参数形状与
 * 返回值解析放在各业务适配器里——通道与工具方法解耦，新增工具零通道改动
 * （Go 侧方法注册表是对偶的另一半，见 engine-server/golang/internal/router）。
 *
 * <p>【DDD 位置】infrastructure 层内部件：domain/application 不直接依赖本接口，
 * 而是依赖各自的业务端口（如 ChecksumPort），由 Go 适配器把业务调用翻译成
 * 本通道的 call()——依赖方向依然是 interfaces → application → domain ← infrastructure。
 */
public interface GoChannel {

    /** 通道名（日志与健康展示用）。 */
    String channelName();

    /** 拉起子进程并完成 sys.ping 握手（幂等：已运行则直接返回）。 */
    void start() throws IOException;

    /** 停止子进程（优雅 → 超时强杀，见 GoProcessLauncher.stop）。 */
    void close();

    /** 子进程是否存活。 */
    boolean isAlive();

    /**
     * 一问一答核心：发 {@code {"id":n,"method":...,"params":...}} 帧并等待同 id 响应帧。
     *
     * @param method 方法名（命名空间.动作，如 "sys.ping"）
     * @param params 参数对象（可为空 ObjectNode）
     * @return result 节点的 JSON 字符串（由调用方反序列化成业务 DTO）
     * @throws io.github.dekkerding.engine.domain.exception.EngineException
     *         引擎不可用 / 超时 / Go 侧错误帧，一律包装为 downstream 错误
     */
    String call(String method, ObjectNode params);
}
