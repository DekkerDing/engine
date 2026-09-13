package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.ServerApplication;
import io.github.dekkerding.engine.application.DocumentApplicationService;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import io.github.dekkerding.engine.infrastructure.go.protocol.GoProtocol;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteVectorStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 副本同步闭环 —— 任务 4.4 的验收点（spec「摄取后立即可检索」场景）：
 * <pre>
 * 摄取完成（返回）时  → 副本已含新块（复制先于摄取返回）→ vector_count = 主索引 size
 * 副本直查           → vector.search 命中新文档的块（Go 轨数据在场的直接证据）
 * 删除联动           → vector.delete 后副本计数随减
 * 重启灌入           → 副本被清空后 preload() 重灌，vector_count 回满
 * </pre>
 *
 * <p>【context 姿势】与 {@link GoDegradedIngestIT} 同款：go-toolbox Profile +
 * enabled=true（已被验证能拉起的装配组合）；被测的副本同步只认第一级开关，
 * 与 Profile 无关——用已验证组合省一次装配排障。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = ServerApplication.class)
@ActiveProfiles("go-toolbox")
class GoReplicaSyncIT {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void goToolboxConfig(DynamicPropertyRegistry registry) {
        registry.add("engine.go.enabled", () -> "true");
        registry.add("engine.persistence.sqlite-path", () -> tempDir.resolve("replica.db").toString());
        registry.add("engine.lucene.index-path", () -> tempDir.resolve("lucene").toString());
        registry.add("engine.documents.upload-dir", () -> tempDir.resolve("documents").toString());
        registry.add("engine.search.min-vector-score", () -> "-1");
        registry.add("engine.search.cache.search-result.enabled", () -> "false");
    }

    @BeforeAll
    static void requireGoToolchain() {
        assumeTrue(GoDegradedIngestIT.engineAvailable(),
                "本机无 Go 工具链/二进制，跳过副本同步闭环");
    }

    @Autowired
    private DocumentApplicationService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SqliteVectorStore vectorStore;

    @Autowired
    private GoVectorReplica replica;

    @Autowired
    private GoToolboxProvider toolbox;

    @Autowired
    private SqliteConnectionManager connectionManager;

    /** 摄取一篇多块文档并等待完成（go run 编译已缓存在前序测试，60s 足够）。 */
    private Document ingest(String name) throws InterruptedException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            content.append("第").append(i).append("段：红塔矗立在公园中央，塔影倒映湖面。");
        }
        Document doc = documentService.upload(name,
                content.toString().getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (System.currentTimeMillis() < deadline) {
            Document current = documentRepository.findById(doc.getId()).orElse(null);
            if (current != null && current.getStatus() == DocumentStatus.COMPLETED) {
                return current;
            }
            if (current != null && current.getStatus() == DocumentStatus.FAILED) {
                throw new AssertionError("摄取失败: " + current.getErrorMessage());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("摄取未在 60s 内完成: " + doc.getId());
    }

    @Test
    void 摄取完成立刻副本可查_计数一致_删除联动() throws Exception {
        Document doc = ingest("副本同步一.txt");

        // 「复制先于摄取返回」：COMPLETED 后立即查 sys.stats——副本计数 = 主索引计数
        int expected = (int) vectorStore.count();
        assertTrue(expected > 0, "前置：摄取应产生向量条目");
        assertEquals(expected, toolbox.stats().vector_count,
                "摄取返回后副本计数必须已与主索引一致（同步复制非异步）");

        // 副本直查命中新块（不经 Java 路由——Go 侧数据的直接证据）
        GoProtocol.HashResult hash = toolbox.generateHash("红塔 公园", 512);
        float[] query = new float[hash.vector.size()];
        for (int i = 0; i < query.length; i++) {
            query[i] = hash.vector.get(i).floatValue();
        }
        List<GoProtocol.VectorSearchResult.HitItem> hits =
                replica.search(query, 10, "text", "go-hash-degraded");
        assertTrue(hits.stream().anyMatch(h -> doc.getId().equals(h.document_id)),
                "vector.search 应命中刚摄取文档的块，实际: " + hits.size() + " 条");

        // 删除联动：vector.delete 随主索引同减
        documentService.deleteDocument(doc.getId());
        assertEquals(0, vectorStore.count(), "库里只有该文档，删除后主索引应空");
        assertEquals(0, toolbox.stats().vector_count, "副本计数应随删除同步归零");
    }

    @Test
    void 重启灌入_副本清空后preload回满() throws Exception {
        Document doc = ingest("副本同步二.txt");
        assertEquals(vectorStore.count(), toolbox.stats().vector_count,
                "前置：摄取后两侧计数一致");

        // 模拟"副本丢失"（进程重启即空白副本）：直删副本数据
        replica.remove(doc.getId());
        assertEquals(0, toolbox.stats().vector_count, "前置：副本已清空");

        // 重启语义：新 store 指向同一 SQLite → preload() 全量重灌
        SqliteVectorStore restarted = new SqliteVectorStore(connectionManager, Optional.of(replica));
        restarted.preload();

        assertEquals(vectorStore.count(), restarted.count(), "重启后主索引恢复");
        assertEquals(vectorStore.count(), toolbox.stats().vector_count,
                "preload 的 index.replace 应顺带把副本灌满（vector_count = 索引 size）");
        assertNotNull(documentRepository.findById(doc.getId()).orElse(null));
    }
}
