package io.github.dekkerding.engine.domain.exception;

/**
 * 业务异常基类 —— 领域层的"错误语言"。
 *
 * <p>【教学注释 · 为什么放在 domain 层】
 * 异常本身就是业务规则的一部分（"文件超过 50MB 不允许上传"是业务规则）。
 * 领域层不依赖 Spring，所以继承 RuntimeException 而不是框架异常——
 * 这样 domain 层可以抛出它，而由 interfaces 层的 GlobalExceptionHandler 统一翻译成 HTTP 响应。
 *
 * <p>【教学注释 · 为什么继承 RuntimeException（非受检异常）】
 * 受检异常（extends Exception）会强迫每个调用者 try-catch 或再声明，层层传染；
 * 现代实践（Spring/领域驱动）倾向非受检异常 + 全局统一处理，让业务代码保持干净。
 */
public class EngineException extends RuntimeException {

    private final int code;

    public EngineException(int code, String message) {
        super(message);
        this.code = code;
    }

    public EngineException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 业务码：与 ApiResponse.code 同一套语义（400/404/500/503） */
    public int getCode() {
        return code;
    }

    /** 400：请求参数不合法（前端传错） */
    public static EngineException badRequest(String message) {
        return new EngineException(400, message);
    }

    /** 404：资源不存在 */
    public static EngineException notFound(String message) {
        return new EngineException(404, message);
    }

    /** 409：领域状态机拒绝（如渲染未提交的需求、给过期工件存修订） */
    public static EngineException conflict(String message) {
        return new EngineException(409, message);
    }

    /** 422：完整性闸门拒绝（需求缺验收标准等——message 携带逐项缺失清单） */
    public static EngineException unprocessable(String message) {
        return new EngineException(422, message);
    }

    /** 500：服务器内部错误（我方 bug） */
    public static EngineException internal(String message, Throwable cause) {
        return new EngineException(500, message, cause);
    }

    /** 503：下游依赖不可用（Python 引擎挂了、模型没加载） */
    public static EngineException downstream(String message, Throwable cause) {
        return new EngineException(503, message, cause);
    }
}
