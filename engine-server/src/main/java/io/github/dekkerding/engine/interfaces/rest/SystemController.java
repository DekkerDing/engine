package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.SystemQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统状态接口 —— 前端 System 页的数据来源。
 *
 * <p>【教学注释 · Controller 的职责边界】
 * Controller 只做三件事：接请求 → 调应用服务 → 包响应。不写业务逻辑、不直接碰仓储。
 * 这样换一种接入方式（比如 gRPC）时业务代码完全复用。
 */
@RestController
@RequestMapping("/system")
public class SystemController {

    private final SystemQueryService systemQueryService;

    /** 【教学注释 · 构造器注入】比 @Autowired 字段注入好：字段可 final、可单测 new 出来、依赖一目了然 */
    public SystemController(SystemQueryService systemQueryService) {
        this.systemQueryService = systemQueryService;
    }

    /**
     * 聚合健康状态：Java 应用 + Python 通道 + 模型注册表 + 降级标志。
     * 前端顶栏黄条（DegradedBanner）和 System 页都消费这个接口。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(systemQueryService.aggregateHealth());
    }
}
