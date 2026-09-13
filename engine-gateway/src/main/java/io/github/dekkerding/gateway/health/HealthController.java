package io.github.dekkerding.gateway.health;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dekkerding.gateway.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聚合健康端点 —— GET /api/system/health。
 *
 * <p>【路由优先级】精确匹配 /api/system/health 优先于 ProxyController 的 /api/**
 * 通配（Spring 路由最长匹配原则），网关自己的健康不会被代理转发。
 *
 * <p>【聚合结构】（spec gateway-routing「聚合健康端点」+ frontend-app 仪表盘三卡片）
 * <pre>
 * data.status    整链路判定：UP / DEGRADED（部分异常）/ DOWN
 * data.gateway   网关自身（能响应本请求即存活）
 * data.server    业务服务存活（周期探测结果 + 失败原因 + 最后成功时间）
 * data.engine    Python 引擎（server /system/health 的 engine 段透传 + 语义化 status）
 * data.documents 文档统计（顺手透传，仪表盘一次请求拿全所有数据）
 * </pre>
 *
 * <p>【教学注释 · 为什么网关也返回信封】前端只有一个统一客户端
 * （frontend/src/api/client.ts），所有响应都走同一套 code≠0 抛错逻辑——
 * 网关返回裸 JSON 会逼前端为它单独写解析分支，统一信封是"契约一致性"的代价最低做法。
 */
@RestController
public class HealthController {

    private final UpstreamHealthService upstreamHealthService;

    public HealthController(UpstreamHealthService upstreamHealthService) {
        this.upstreamHealthService = upstreamHealthService;
    }

    @GetMapping("/api/system/health")
    public ApiResponse<Map<String, Object>> health() {
        boolean serverUp = upstreamHealthService.isServerUp();

        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("application", "engine-gateway");
        gateway.put("status", "UP");   // 本身能执行到这里就是活的——自证存活

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("application", "engine-server");
        server.put("status", serverUp ? "UP" : "DOWN");
        server.put("lastError", serverUp ? null : upstreamHealthService.getLastError());
        Instant lastSuccessAt = upstreamHealthService.getLastSuccessAt();
        server.put("lastSuccessAt", lastSuccessAt == null ? null : lastSuccessAt.toString());

        // Python 引擎段：server 透传数据 + 语义化 status（前端卡片直接渲染，无需重复判断逻辑）
        JsonNode engineSection = upstreamHealthService.getEngineSection();
        Map<String, Object> engine;
        if (engineSection == null || engineSection.isNull()) {
            engine = null;   // server 未起过或 Python 通道未初始化：状态未知
        } else {
            engine = new LinkedHashMap<>();
            engine.put("status", engineStatus(engineSection));
            engine.put("detail", engineSection);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", overallStatus(serverUp, engine));
        result.put("gateway", gateway);
        result.put("server", server);
        result.put("engine", engine);
        result.put("documents", upstreamHealthService.getDocumentsSection());
        return ApiResponse.ok(result);
    }

    /** 引擎段 → 语义化状态：ok+非降级=UP；降级=DEGRADED（spec: 降级而非 DOWN）；不 ok=DOWN */
    private String engineStatus(JsonNode engineSection) {
        boolean ok = engineSection.path("ok").asBoolean(false);
        boolean degraded = engineSection.path("degraded").asBoolean(false);
        if (!ok) {
            return "DOWN";
        }
        return degraded ? "DEGRADED" : "UP";
    }

    /**
     * 整链路判定（spec 场景「全链路健康」/「Python 降级联动展示」）：
     * server DOWN → DOWN（链路断裂）；引擎降级/不 ok → DEGRADED（部分异常）；否则 UP。
     */
    private String overallStatus(boolean serverUp, Map<String, Object> engine) {
        if (!serverUp) {
            return "DOWN";
        }
        if (engine != null && !"UP".equals(engine.get("status"))) {
            return "DEGRADED";
        }
        return "UP";
    }
}
