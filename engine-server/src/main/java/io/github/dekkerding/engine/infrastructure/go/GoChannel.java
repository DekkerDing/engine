package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;

import java.io.IOException;

/**
 * Go 引擎通道抽象 —— 与 PythonChannel 对等的"跨语言通信契约"。
 *
 * <p>与 PythonChannel 的核心差异：
 * <ul>
 *   <li>Go 只有 stdio 一种通道（编译后的二进制，没有 Py4J 模式）</li>
 *   <li>Go 协议统一：所有方法调用同一个 send(method, params) 返回 Response</li>
 *   <li>Go 没有模型加载阶段，启动即就绪（纯工具方法，不加载大权重文件）</li>
 *   <li>Go 方法的 result 由调用方自行解包（灵活性 > 类型安全）</li>
 * </ul>
 *
 * <p>【教学注释 · 为什么接口比 PythonChannel 更"薄"】
 *   PythonChannel 为每个 op 定义了独立方法签名（embedTexts / tokenize / rerank...），
 *   这对 Py4J 模式是必须的（方法签名就是远程对象的方法签名）。
 *   Go 引擎走 JSON 行协议——方法只是一个字符串，参数只是 JSON 字节——
 *   所以接口只需要 send() 一个方法，其余交给 GoProtocol 解析。
 *   这就是"协议驱动"vs"方法驱动"的接口差异。
 */
public interface GoChannel extends AutoCloseable {

    /** 通道标识（始终 "go-stdio"） */
    String channelName();

    /** 拉起 Go 子进程并建立通信链路（幂等） */
    void start() throws IOException;

    /** 停止子进程、释放资源（幂等） */
    @Override
    void close();

    /** 子进程是否存活 */
    boolean isAlive();

    /** 发送请求并等待响应帧 */
    GoProtocol.Response send(String method, java.util.Map<String, Object> params);
}