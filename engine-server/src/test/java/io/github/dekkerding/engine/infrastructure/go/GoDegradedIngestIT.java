package io.github.dekkerding.engine.infrastructure.go;

import io.github.dekkerding.engine.ServerApplication;
import io.github.dekkerding.engine.application.DocumentApplicationService;
import io.github.dekkerding.engine.application.SearchApplicationService;
import io.github.dekkerding.engine.application.dto.DocumentDetail;
import io.github.dekkerding.engine.application.dto.SearchResult;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 降级向量化闭环 —— 任务 3.4 的验收点（spec「降级模式接管向量化」场景）：
 * go-toolbox Profile + engine.go.enabled=true 下，摄取走 GoToolboxProvider.embedBatch
 * （hashing.generate，degraded=true），检索链路正常完成——只断链路不断线，
 * 不做哈希向量的相关性断言（伪向量无语义）。
 *
 * <p>【本仓库第一个全应用 @SpringBootTest】此前全是"new + stub"纯单测——
 * 本测试是 Profile 装配矩阵（python 让位 + Go 接管 + registry 不炸）的唯一全真验证。
 * 数据目录全部 @TempDir 隔离（SQLite/Lucene/上传目录），跑完即焚。
 *
 * <p>【min-vector-score=-1 的原因】哈希向量的余弦分布与真实模型完全不同，
 * 语义路命中会被 0.48 的真模型阈值清空——测试目的是"链路不断线"而非相关性，
 * 压掉阈值让语义路命中穿透（全文路照常参与 RRF 融合）。
 *
 * <p>【@BeforeAll assume】Spring context 在首个测试方法前加载——假设必须放在
 * @BeforeAll（先于 context 创建）才能让无 Go 环境整类跳过而非启动失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = ServerApplication.class)
@ActiveProfiles("go-toolbox")
class GoDegradedIngestIT {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void goToolboxConfig(DynamicPropertyRegistry registry) {
        registry.add("engine.go.enabled", () -> "true");
        registry.add("engine.persistence.sqlite-path", () -> tempDir.resolve("test.db").toString());
        registry.add("engine.lucene.index-path", () -> tempDir.resolve("lucene").toString());
        registry.add("engine.documents.upload-dir", () -> tempDir.resolve("documents").toString());
        registry.add("engine.search.min-vector-score", () -> "-1");
        registry.add("engine.search.cache.search-result.enabled", () -> "false");
    }

    @BeforeAll
    static void requireGoToolchain() {
        assumeTrue(engineAvailable(), "本机无 Go 工具链/二进制，跳过降级闭环");
    }

    /** 包级可见：同包的姊妹 IT（GoReplicaSyncIT 等）共用同一探测，避免三份拷贝。 */
    static boolean engineAvailable() {
        try {
            new GoProcessLauncher("", "go").resolve();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private DocumentApplicationService documentService;

    @Autowired
    private SearchApplicationService searchService;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void 摄取到检索闭环_降级向量不断线() throws Exception {
        // 语料：含可检索关键词的中文文本（重复段落撑出多块，覆盖分块+批向量两步）
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            content.append("第").append(i).append("段：红塔矗立在公园中央，塔影倒映湖面，游人如织。");
        }
        Document doc = documentService.upload("降级闭环.txt",
                content.toString().getBytes(StandardCharsets.UTF_8));

        // 摄取是异步的（2 线程池）：轮询到 COMPLETED（含 go run 首次编译，放宽到 90s）
        Document completed = awaitStatus(doc.getId(), DocumentStatus.COMPLETED, 90);
        assertNotNull(completed, "文档应在超时前完成摄取");
        assertTrue(completed.isDegraded(), "哈希兜底摄取必须带 degraded 标志");

        // 向量化确走 Go provider：详情聚合的 modelKey 是实际编码者的身份证
        DocumentDetail detail = documentService.getDocumentDetail(doc.getId());
        assertEquals("go-hash-degraded", detail.getModelKey(),
                "TEXT 模态应被 GoToolboxProvider 接管（python 让位后唯一 provider）");
        assertEquals(512, detail.getDimension());
        assertFalse(detail.getChunks().isEmpty(), "降级摄取同样要完成分块");

        // 检索闭环：语义路（哈希向量）+ 全文路（Lucene）RRF 融合不断线
        SearchResult result = searchService.search("红塔 公园", 10);
        assertNotNull(result);
        assertTrue(result.getTotal() >= 1, "检索应命中刚摄取的文档（全文路保底）");
        boolean hitNewDoc = result.getItems().stream()
                .anyMatch(hit -> doc.getId().equals(hit.getDocumentId()));
        assertTrue(hitNewDoc, "命中列表应包含新摄取文档的块");

        // 删除联动也不断线（副本/索引/全文三处清理后检索不再命中）
        documentService.deleteDocument(doc.getId());
        SearchResult afterDelete = searchService.search("红塔 公园", 10);
        boolean stillThere = afterDelete.getItems().stream()
                .anyMatch(hit -> doc.getId().equals(hit.getDocumentId()));
        assertFalse(stillThere, "删除后该文档不应再被命中");
    }

    /** 轮询文档状态到目标态（超时返回 null）。 */
    private Document awaitStatus(String id, DocumentStatus want, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            Document doc = documentRepository.findById(id).orElse(null);
            if (doc != null && doc.getStatus() == want) {
                return doc;
            }
            if (doc != null && doc.getStatus() == DocumentStatus.FAILED) {
                throw new AssertionError("文档摄取失败（FAILED）: " + doc.getErrorMessage());
            }
            Thread.sleep(200);
        }
        return null;
    }
}
