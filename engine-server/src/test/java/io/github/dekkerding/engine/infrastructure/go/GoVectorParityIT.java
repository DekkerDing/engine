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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 双实现对拍 —— 任务 4.5 的验收点（spec「双实现对拍」场景）：
 * 同查询向量、同 topK、同过滤条件，Java {@code InMemoryVectorIndex}（本地暴力扫）
 * 与 Go {@code vector.search}（并行分片扫）的<b>命中集合与顺序逐条一致</b>，
 * 分数 1e-6 容差（design D5：量化定序合同是逐条对拍的前提——两边
 * topK/sortHits 用同一套"量化分数降序 + documentId 字典序 + chunkIndex 升序"）。
 *
 * <p>【基准侧的构造】生产 store 的索引带副本（检索走 Go 轨），对拍需要"纯本地"基准：
 * 新建无副本 SqliteVectorStore 指向同一 SQLite → preload() 重建主索引——
 * 数据同源（真相库），执行路径独立（本地扫 vs Go 并行扫）。
 *
 * <p>【样本对拍输出】逐查询打印 top-3 三元组与分数（system-out 留痕，
 * 任务验收要求"记录样本对拍输出"）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = ServerApplication.class)
@ActiveProfiles("go-toolbox")
class GoVectorParityIT {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void goToolboxConfig(DynamicPropertyRegistry registry) {
        registry.add("engine.go.enabled", () -> "true");
        registry.add("engine.persistence.sqlite-path", () -> tempDir.resolve("parity.db").toString());
        registry.add("engine.lucene.index-path", () -> tempDir.resolve("lucene").toString());
        registry.add("engine.documents.upload-dir", () -> tempDir.resolve("documents").toString());
        registry.add("engine.search.min-vector-score", () -> "-1");
        registry.add("engine.search.cache.search-result.enabled", () -> "false");
    }

    @BeforeAll
    static void requireGoToolchain() {
        assumeTrue(GoDegradedIngestIT.engineAvailable(),
                "本机无 Go 工具链/二进制，跳过双实现对拍");
    }

    @Autowired
    private DocumentApplicationService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private GoVectorReplica replica;

    @Autowired
    private GoToolboxProvider toolbox;

    @Autowired
    private SqliteConnectionManager connectionManager;

    private Document ingest(String name, String corpus) throws InterruptedException {
        Document doc = documentService.upload(name, corpus.getBytes(StandardCharsets.UTF_8));
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

    /** 语料：不同主题的重复段落撑出多块（块数够 topK 档位才有截断意义）。 */
    private static String corpus(String sentence, int repeat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeat; i++) {
            sb.append("第").append(i).append("段：").append(sentence);
        }
        return sb.toString();
    }

    private float[] hashQuery(String text) {
        GoProtocol.HashResult hash = toolbox.generateHash(text, 512);
        float[] query = new float[hash.vector.size()];
        for (int i = 0; i < query.length; i++) {
            query[i] = hash.vector.get(i).floatValue();
        }
        return query;
    }

    @Test
    void 同查询同topK同过滤_命中集合与顺序逐条一致() throws Exception {
        // 三篇不同主题语料（哈希向量伪随机——查询间分数分布有区分度）
        ingest("对拍一.txt", corpus("红塔矗立在公园中央，塔影倒映湖面，游人如织。", 25));
        ingest("对拍二.txt", corpus("远山如黛，层峦叠嶂，云雾缭绕山间小径。", 25));
        ingest("对拍三.txt", corpus("机房服务器风扇轰鸣，指示灯在黑暗中闪烁。", 25));

        // 纯本地基准：无副本 store，同库 preload
        SqliteVectorStore local = new SqliteVectorStore(connectionManager, Optional.empty());
        local.preload();
        // 每篇 ~500 字（400 rune 目标块长）→ 2 块 × 3 篇 = 6 块：topK=3 截断 / topK=25 全量两档
        assertTrue(local.count() >= 6, "前置：三篇文档应产出足量块（实际 " + local.count() + "）");

        String[][] queries = {
                {"红塔 公园", "主题一近邻查询"},
                {"远山 云雾", "主题二近邻查询"},
                {"服务器 机房", "主题三近邻查询"},
                {"红塔 远山 机房", "跨主题混合查询"},
        };
        int[] topKs = {3, 25};

        int compared = 0;
        for (String[] q : queries) {
            float[] query = hashQuery(q[0]);
            for (int topK : topKs) {
                List<io.github.dekkerding.engine.domain.model.vector.VectorHit> java =
                        local.search(query, topK, "text", "go-hash-degraded");
                List<GoProtocol.VectorSearchResult.HitItem> go =
                        replica.search(query, topK, "text", "go-hash-degraded");

                assertEquals(java.size(), go.size(),
                        "[" + q[1] + " topK=" + topK + "] 命中数不一致");
                for (int i = 0; i < java.size(); i++) {
                    assertEquals(java.get(i).getEntry().getDocumentId(), go.get(i).document_id,
                            "[" + q[1] + " topK=" + topK + "] 第 " + i + " 位文档不一致");
                    assertEquals(java.get(i).getEntry().getChunkIndex(), go.get(i).chunk_index,
                            "[" + q[1] + " topK=" + topK + "] 第 " + i + " 位块序不一致");
                    assertEquals(java.get(i).getScore(), go.get(i).score, 1e-6,
                            "[" + q[1] + " topK=" + topK + "] 第 " + i + " 位分数超 1e-6 容差");
                    compared++;
                }
                // 样本对拍输出（每查询首档 topK 打 top-3 留痕）
                if (topK == topKs[0]) {
                    System.out.printf("[对拍样本] %s: java top1=(%s,#%d,%.6f) | go top1=(%s,#%d,%.6f)%n",
                            q[1],
                            java.get(0).getEntry().getDocumentId().substring(0, 8),
                            java.get(0).getEntry().getChunkIndex(), java.get(0).getScore(),
                            go.get(0).document_id.substring(0, 8),
                            go.get(0).chunk_index, go.get(0).score);
                }
            }
        }
        assertTrue(compared >= 6 * 3, "对拍覆盖应有足量条目（实际逐条比对 " + compared + " 条）");
    }
}
