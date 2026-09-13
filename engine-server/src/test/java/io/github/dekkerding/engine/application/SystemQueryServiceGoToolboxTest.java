package io.github.dekkerding.engine.application;

import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.model.engine.GoToolboxStatus;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.domain.repository.GoToolboxStatusQuery;
import io.github.dekkerding.engine.domain.repository.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 健康聚合 goToolbox 段 —— 任务 6.1 的单元验收：N/A / UP / DOWN 三态 +
 * 「既有键结构不变」（新增段不挤掉 application/engine/documents 等既有键）。
 *
 * <p>【测试姿势】直接 new 服务类（不拉 Spring 上下文）——构造参数全可控，
 * Optional.empty() 精确模拟"engine.go.enabled=false 时容器无 Go bean"的装配事实。
 */
class SystemQueryServiceGoToolboxTest {

    // ---------- 空实现 fake：goToolbox 段测试不触碰 documents 段的返回值 ----------

    private static class EmptyDocumentRepository implements DocumentRepository {
        @Override public void save(Document document) { }
        @Override public void update(Document document) { }
        @Override public Optional<Document> findById(String id) { return Optional.empty(); }
        @Override public List<Document> findAll() { return Collections.emptyList(); }
        @Override public void deleteById(String id) { }
        @Override public void saveChunks(String documentId, List<TextChunk> chunks) { }
        @Override public List<TextChunk> findChunks(String documentId) { return Collections.emptyList(); }
        @Override public void deleteChunks(String documentId) { }
    }

    private static class EmptyVectorStore implements VectorStore {
        @Override public void save(String documentId, List<VectorEntry> entries) { }
        @Override public List<VectorHit> search(float[] queryVector, int topK,
                                                String sourceType, String modelKey) {
            return Collections.emptyList();
        }
        @Override public void deleteByDocument(String documentId) { }
        @Override public long count() { return 0; }
    }

    private SystemQueryService service(GoToolboxStatusQuery goQuery) {
        return new SystemQueryService(
                Optional.empty(),                      // Python 引擎缺席（与本测试无关）
                Optional.ofNullable(goQuery),          // Go 查询端口：null = 关闭态
                new EmptyDocumentRepository(),
                new EmptyVectorStore(),
                Collections.emptyList());              // 无 EmbeddingProvider
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> goToolboxSection(Map<String, Object> aggregated) {
        return (Map<String, Object>) aggregated.get("goToolbox");
    }

    @Test
    void 关闭态_无bean_NA段显式呈现() {
        Map<String, Object> aggregated = service(null).aggregateHealth();

        Map<String, Object> section = goToolboxSection(aggregated);
        assertEquals(false, section.get("enabled"), "默认态：engine.go.enabled=false 无 Go bean");
        assertEquals("N/A", section.get("status"), "N/A 而非缺席——功能在场但未启用");
        assertFalse(section.containsKey("version"), "关闭态不该有运行时指标键");
    }

    @Test
    void 开启态_引擎健康_UP段带指标() {
        GoToolboxStatusQuery up = () -> new GoToolboxStatus(
                true, "0.2.0", 3,
                Arrays.asList("sys.ping", "text.chunk", "vector.search"), null);

        Map<String, Object> section = goToolboxSection(service(up).aggregateHealth());

        assertEquals(true, section.get("enabled"));
        assertEquals("UP", section.get("status"));
        assertEquals("0.2.0", section.get("version"), "版本自 sys.stats——Go 引擎在跑的直接证据");
        assertEquals(3, section.get("vectorCount"));
        assertEquals(3, ((List<?>) section.get("tools")).size());
        assertNull(section.get("lastError"));
    }

    @Test
    void 开启态_探活失败_DOWN段带原因() {
        GoToolboxStatusQuery down = () -> GoToolboxStatus.down("Go 进程未运行（未启动或已退出）");

        Map<String, Object> section = goToolboxSection(service(down).aggregateHealth());

        assertEquals(true, section.get("enabled"));
        assertEquals("DOWN", section.get("status"));
        assertTrue(((String) section.get("lastError")).contains("未运行"),
                "DOWN 必须带人能读懂的原因: " + section.get("lastError"));
    }

    @Test
    void 既有键结构不变_新增段不挤掉旧键() {
        Map<String, Object> aggregated = service(null).aggregateHealth();

        // 6.1 验收点：既有键结构不变（前端三卡片消费的键一个不能少）
        assertEquals("engine-server", aggregated.get("application"));
        assertEquals("UP", aggregated.get("status"));
        assertTrue(aggregated.containsKey("engine"), "engine 段保持（null 值也是合法在场）");
        assertTrue(aggregated.containsKey("documents"));
        assertTrue(aggregated.containsKey("goToolbox"), "新增段在场");
        // goToolbox DOWN 不参与整体判定（加速器语义）——本例关闭态整体仍 UP
        assertEquals("UP", aggregated.get("status"));
    }
}
