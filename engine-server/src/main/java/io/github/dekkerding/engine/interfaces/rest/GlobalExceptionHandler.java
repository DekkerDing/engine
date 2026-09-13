package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器 —— 把所有异常统一翻译成 {@link ApiResponse} 信封。
 *
 * <p>【教学注释 · @RestControllerAdvice】
 * AOP 思想的落地：不用在每个 Controller 里写 try-catch，这里声明一次，
 * 全部 Controller 抛出的异常都会被拦截。这是"横切关注点分离"的标准做法。
 *
 * <p>【异常分级处理策略】（规范见 docs/java-coding-standards.md）
 * | 异常类型              | HTTP | 日志级别 | 说明                       |
 * |-----------------------|------|----------|----------------------------|
 * | EngineException       | 自带 | warn     | 预期内业务错误，无需堆栈   |
 * | 参数校验失败          | 400  | warn     | 字段级错误信息拼进 message |
 * | 上传超限              | 400  | warn     | 用户体验友好提示           |
 * | 其他 Exception        | 500  | error    | 未预期错误，必须记完整堆栈 |
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：HTTP 状态用异常自带的传输语义，信封 code 映射到责任分段 */
    @ExceptionHandler(EngineException.class)
    public ResponseEntity<ApiResponse<Void>> handleEngine(EngineException e) {
        log.warn("业务异常: http={}, envelope={}, message={}",
                e.getCode(), envelopeCode(e.getCode()), e.getMessage());
        return ResponseEntity
                .status(e.getCode())
                .body(ApiResponse.error(envelopeCode(e.getCode()), e.getMessage()));
    }

    /** @Valid 校验失败：提取第一个字段错误（够用且信息最聚焦） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("参数不合法");
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.error(1000, detail));
    }

    /** 请求体缺失/不可解析（无 body 或 JSON 语法错误）：参数段 400，warn 不带堆栈 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("请求体缺失或不可解析: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(1000, "请求体缺失或格式不合法"));
    }

    /** 上传文件超限：Multipart 层面抛出，早于业务代码 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(1000, "文件超过大小限制（50MB）"));
    }

    /** 兜底：任何未预期的异常都不允许把堆栈泄露给前端（安全），但要完整记录（可排查） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        // 【日志规范】error 级别必须把异常对象作为最后一个参数传入，logback 才会打印堆栈
        log.error("未预期异常", e);
        return ResponseEntity
                .status(500)
                .body(ApiResponse.error(5000, "服务内部错误，请查看服务端日志"));
    }

    /**
     * HTTP 传输语义 → 业务码分段（design.md D9）：
     * 400/422→1xxx 参数（422 = 需求工厂闸门：提交内容不完整，前端提示补全输入）、
     * 404/409→2xxx 领域（409 = 状态机拒绝，如渲染未提交的需求）、
     * 503→3xxx Python 下游、其余→5xxx 系统。
     * HTTP 管"传输层怎么了"，分段管"谁的责任"——前端按分段决定交互。
     */
    private int envelopeCode(int httpStatus) {
        switch (httpStatus) {
            case 400: return 1000;
            case 422: return 1000;
            case 404: return 2000;
            case 409: return 2000;
            case 503: return 3000;
            default:  return 5000;
        }
    }
}
