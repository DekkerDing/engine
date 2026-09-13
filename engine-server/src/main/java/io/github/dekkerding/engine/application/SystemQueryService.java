package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.model.engine.EngineStatus;
import io.github.dekkerding.engine.domain.model.engine.GoToolboxStatus;
import io.github.dekkerding.engine.domain.model.vector.EmbeddingModality;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.domain.repository.EmbeddingProvider;
import io.github.dekkerding.engine.domain.repository.EngineStatusQuery;
import io.github.dekkerding.engine.domain.repository.GoToolboxStatusQuery;
import io.github.dekkerding.engine.domain.repository.VectorStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统状态应用服务 —— 聚合各子系统健康信息。
 *
 * <p>【教学注释 · 应用层的定位】
 * 应用服务是"用例编排者"：一个用户动作（查看系统健康）需要哪些能力、按什么顺序组装，
 * 由它说了算；但每一步"怎么做"都委托给领域层/端口。
 *
 * <p>【聚合结构】（spec frontend-app 仪表盘的三块数据源，一次请求全拿齐）
 * <pre>
 * application   server 自身（名称 + 存活状态）
 * engine        Python 引擎（通道/模型/维度/降级——网关原样透传这一段）
 * documents     文档统计（总数/各状态计数/块数/向量条数/降级文档数）
 * </pre>
 *
 * <p>【教学注释 · Optional 依赖注入】
 * P0 阶段 Python 通道还没实现（P1 才有），把构造参数声明为 {@code Optional<EngineStatusQuery>}：
 * 容器里有该 bean 就注入实值，没有就注入空——工程在任何阶段都可启动。
 * 【坑】{@code @Autowired(required=false)} 放在构造器上并不会让参数可选，
 * 单一构造器仍会被强制注入（实测启动失败），Optional 才是正解。
 */
@Service
public class SystemQueryService {

    private final Optional<EngineStatusQuery> engineStatusQuery;
    private final Optional<GoToolboxStatusQuery> goToolboxStatusQuery;
    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final EmbeddingProvider textEmbeddingProvider;

    public SystemQueryService(Optional<EngineStatusQuery> engineStatusQuery,
                              Optional<GoToolboxStatusQuery> goToolboxStatusQuery,
                              DocumentRepository documentRepository,
                              VectorStore vectorStore,
                              List<EmbeddingProvider> providers) {
        this.engineStatusQuery = engineStatusQuery;
        this.goToolboxStatusQuery = goToolboxStatusQuery;
        this.documentRepository = documentRepository;
        this.vectorStore = vectorStore;
        // 注册制选 TEXT provider（与摄取/检索同一口径）：仪表盘展示的"当前模型"必须与实际编码的一致
        this.textEmbeddingProvider = providers.stream()
                .filter(p -> p.modality() == EmbeddingModality.TEXT)
                .findFirst()
                .orElse(null);
    }

    /**
     * 聚合健康数据：{application, status, engine:{...}, goToolbox:{...}, documents:{...}}。
     * engine 为 null 表示通道尚未初始化（比如测试环境没启用 Python Profile）；
     * goToolbox 为 N/A 表示 Go 引擎未启用（engine.go.enabled=false 默认态）。
     */
    public Map<String, Object> aggregateHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", "engine-server");
        result.put("status", "UP");
        result.put("engine", engineSection());
        result.put("goToolbox", goToolboxSection());
        result.put("documents", documentsSection());
        return result;
    }

    /**
     * Go 工具箱段（gotoolbox 健康聚合）：N/A / UP / DOWN 三态。
     * 与 engine 段不同，本段<b>不参与整体 status 判定</b>——Go 引擎是加速器不是必需品
     * （挂了检索降级本地扫、摄取降级哈希，服务依然正确可用），DOWN 是"性能退化提示"
     * 而非"服务不可用"，不该把整体拖成 DEGRADED 稀释 engine 段的语义。
     */
    private Map<String, Object> goToolboxSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        if (!goToolboxStatusQuery.isPresent()) {
            // 关闭态显式呈现 N/A（而非缺席）：运维能看到"功能在场但未启用"
            section.put("enabled", false);
            section.put("status", "N/A");
            return section;
        }
        GoToolboxStatus status = goToolboxStatusQuery.get().status();
        section.put("enabled", true);
        section.put("status", status.isOk() ? "UP" : "DOWN");
        section.put("version", status.getVersion());
        section.put("vectorCount", status.getVectorCount());
        section.put("tools", status.getTools());
        section.put("lastError", status.getLastError());
        return section;
    }

    /** Python 引擎段：网关透传给前端，仪表盘健康卡片的直接数据源 */
    private Map<String, Object> engineSection() {
        if (!engineStatusQuery.isPresent()) {
            return null;
        }
        EngineStatus engine = engineStatusQuery.get().status();
        Map<String, Object> engineMap = new LinkedHashMap<>();
        engineMap.put("ok", engine.isOk());
        engineMap.put("degraded", engine.isDegraded());
        engineMap.put("channel", engine.getChannel());
        engineMap.put("loadedModels", engine.getLoadedModels());
        engineMap.put("dimension", engine.getDimension());
        engineMap.put("lastError", engine.getLastError());
        // 当前生效的模型键与维度：取自实际执行编码的 provider（而非配置快照）——展示即真相
        if (textEmbeddingProvider != null) {
            engineMap.put("modelKey", textEmbeddingProvider.modelKey());
        }
        return engineMap;
    }

    /**
     * 文档统计段：全部计数在内存里对 findAll() 走一遍——学习规模（百~千文档）
     * 毫秒级；上量后再换 SQL count 聚合，接口形状不变。
     */
    private Map<String, Object> documentsSection() {
        List<Document> all = documentRepository.findAll();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (DocumentStatus status : DocumentStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        int chunkTotal = 0;
        int degradedCount = 0;
        for (Document document : all) {
            byStatus.put(document.getStatus().name(), byStatus.get(document.getStatus().name()) + 1L);
            chunkTotal += document.getChunkCount();
            if (document.isDegraded()) {
                degradedCount++;
            }
        }
        Map<String, Object> documents = new LinkedHashMap<>();
        documents.put("total", all.size());
        documents.put("byStatus", byStatus);
        documents.put("chunkTotal", chunkTotal);
        documents.put("vectorTotal", vectorStore.count());
        documents.put("degraded", degradedCount);
        return documents;
    }
}
