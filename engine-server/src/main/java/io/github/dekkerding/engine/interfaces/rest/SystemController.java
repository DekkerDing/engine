package io.github.dekkerding.engine.interfaces.rest;

import io.github.dekkerding.engine.application.SystemQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统状态接口 —— 前端 System 页的数据来源。
 *
 * <p>【教学注释 · Controller 的职责边界】
 * Controller 只做三件事：接请求 → 调应用服务 → 包响应。不写业务逻辑、不直接碰仓储。
 * 这样换一种接入方式（比如 gRPC）时业务代码完全复用。
 *
 * <p>【单 JAR 合体 · 健康聚合内化】单 JAR 合体前，本端点返回
 * {application, status, engine, documents}，由网关周期探测后补 gateway/server
 * 段与整体判定；合体后同进程直调（实时计算，无 10s 探测缓存延迟），
 * 本类继承网关的聚合职责，输出契约逐字段不变（前端三卡片零改动）：
 * <pre>
 * status     整体判定：engine null → UP；engine 非 UP → DEGRADED
 *            （server 同进程自证恒 UP，判定表坍缩为 engine 单维度）
 * gateway    {application:"engine-gateway", status:"UP"}——保留段名兼容前端卡片
 * server     {application, status:"UP", lastError:null, lastSuccessAt:now}
 * engine     原始段语义化为 {status: UP/DEGRADED/DOWN, detail:原始段}；null 保持 null
 * goToolbox  Go 引擎段 {enabled, status: N/A/UP/DOWN, version, vectorCount...}——
 *            不参与整体判定（加速器非必需品：DOWN = 性能退化提示，检索/摄取有
 *            降级兜底，见 SystemQueryService.goToolboxSection 注释）
 * documents  统计段原样透传
 * </pre>
 */
@RestController
@RequestMapping("/api/system")
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
        return ApiResponse.ok(consolidatedHealth());
    }

    /** 合体健康组装：进程内直调聚合服务 + gateway 段 + 整体判定（见类注释契约） */
    private Map<String, Object> consolidatedHealth() {
        Map<String, Object> aggregated = systemQueryService.aggregateHealth();

        // gateway 段：网关能力已并入本进程，恒 UP 自证；段名保留（前端三卡片零改动）
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("application", "engine-gateway");
        gateway.put("status", "UP");

        // server 段：同进程自证恒 UP（能执行到这里的本进程必然活着），实时打点
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("application", "engine-server");
        server.put("status", "UP");
        server.put("lastError", null);
        server.put("lastSuccessAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        // engine 段：原始结构（ok/degraded/channel/...）语义化为 {status, detail}，null 保持 null
        Object engine = semanticEngine(aggregated.get("engine"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", overallStatus(engine));
        result.put("gateway", gateway);
        result.put("server", server);
        result.put("engine", engine);
        // goToolbox 段：整体判定不看它（见类注释）——段在即兼容（前端多出来的键不渲染）
        result.put("goToolbox", aggregated.get("goToolbox"));
        result.put("documents", aggregated.get("documents"));
        return result;
    }

    /**
     * 引擎段语义化（与原网关判定逐条对齐）：
     * ok=false → DOWN；ok=true 且 degraded=true → DEGRADED；否则 UP。
     * detail 携带原始段（channel/loadedModels/dimension/lastError/modelKey…）。
     */
    private Object semanticEngine(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> rawMap = (Map<?, ?>) raw;
        boolean ok = Boolean.TRUE.equals(rawMap.get("ok"));
        boolean degraded = Boolean.TRUE.equals(rawMap.get("degraded"));
        String status = !ok ? "DOWN" : (degraded ? "DEGRADED" : "UP");

        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("status", status);
        engine.put("detail", raw);
        return engine;
    }

    /**
     * 整体判定（与原网关判定表逐条对齐；server 恒 UP 后坍缩为 engine 单维度）：
     * engine null（通道未初始化，如未启用 Python）→ 视为"状态未知"，不拖垮整体 → UP；
     * engine 存在但非 UP → DEGRADED；否则 UP。
     */
    private String overallStatus(Object engine) {
        if (engine == null) {
            return "UP";
        }
        return "UP".equals(((Map<?, ?>) engine).get("status")) ? "UP" : "DEGRADED";
    }
}
