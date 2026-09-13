package io.github.dekkerding.engine.infrastructure.fulltext;

import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.model.search.TextHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lucene 全文索引单测 —— 任务 2.3 的验收点：
 * SmartCN 中文关键词命中、按文档删除后不再命中、幂等重写、高亮片段。
 */
class LuceneFullTextIndexTest {

    @TempDir
    Path tempDir;

    private LuceneFullTextIndex index;

    @BeforeEach
    void 建索引() {
        index = new LuceneFullTextIndex(tempDir.resolve("lucene").toString());
    }

    @AfterEach
    void 关索引() {
        index.shutdown(); // 复用 @PreDestroy 逻辑：测试里显式关，释放目录句柄（Windows 下文件锁敏感）
    }

    private void seed() {
        index.indexDocument("doc-1", Arrays.asList(
                new TextChunk("doc-1", 0, "公园的中央矗立着一座红塔，塔身在夕阳下泛着暖红色。"),
                new TextChunk("doc-1", 1, "红塔的旁边开满了小花，路过的人都会停下脚步。")));
        index.indexDocument("doc-2", Collections.singletonList(
                new TextChunk("doc-2", 0, "本文档介绍向量检索的基本原理与工程实践。")));
    }

    @Test
    void 中文关键词命中对应文档() {
        seed();

        List<TextHit> hits = index.search("红塔", 10);

        assertTrue(hits.size() >= 1);
        // 命中的必须全部来自 doc-1（"红塔"只出现在 doc-1）——SmartCN 把"红塔"切成词项后精确倒排命中
        for (TextHit hit : hits) {
            assertEquals("doc-1", hit.getDocumentId());
        }
        assertEquals(0, hitContaining(hits, "doc-2").size());
    }

    @Test
    void 高亮片段包含em标记() {
        seed();

        List<TextHit> hits = index.search("红塔", 10);

        TextHit first = hits.get(0);
        assertNotNull(first.getHighlight(), "命中词项时应有高亮片段");
        assertTrue(first.getHighlight().contains("<em>"), "高亮应包 em 标签: " + first.getHighlight());
        assertTrue(first.getHighlight().contains("红塔"));
    }

    @Test
    void 删除文档后不再命中() {
        seed();
        assertEquals(2, index.search("红塔", 10).size()); // 删除前：两块都命中

        index.deleteDocument("doc-1");

        assertEquals(0, index.search("红塔", 10).size(), "doc-1 的块必须全部从索引消失");
        // 其它文档不受影响
        assertEquals(1, index.search("向量检索", 10).size());
    }

    @Test
    void 同文档重写幂等_不产生重复块() {
        seed();

        // 重传：2 块替换为 1 块，"红塔"命中数应从 2 变 1 而不是 3
        index.indexDocument("doc-1", Collections.singletonList(
                new TextChunk("doc-1", 0, "只有一块也提到红塔。")));

        List<TextHit> hits = index.search("红塔", 10);
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).getChunkIndex());
    }

    @Test
    void 多词查询按相关性排序() {
        index.indexDocument("doc-a", Collections.singletonList(
                new TextChunk("doc-a", 0, "红塔 红塔 红塔：塔是这段的主角，花只是背景。")));
        index.indexDocument("doc-b", Collections.singletonList(
                new TextChunk("doc-b", 0, "小花在塔边开放，塔只被顺带提了一次。")));

        List<TextHit> hits = index.search("红塔", 10);

        // BM25：词频更高（3 次 vs 0 次"红塔"词项）的块排前面
        assertEquals("doc-a", hits.get(0).getDocumentId());
        assertTrue(hits.get(0).getScore() >= hits.get(hits.size() - 1).getScore());
    }

    @Test
    void topK截断() {
        seed();

        assertEquals(1, index.search("红塔", 1).size());
    }

    @Test
    void 无命中返回空列表() {
        seed();

        // 【用例设计坑】查询里不能带"的/了/在"这类虚词——SmartCN 会切出单字词项，
        // 与文档里的虚词匹配造成"意外命中"。BM25 下虚词 idf 极低排名垫底，不影响真实检索质量
        assertTrue(index.search("量子纠缠", 10).isEmpty());
    }

    @Test
    void 空查询返回空列表不报错() {
        assertTrue(index.search("", 10).isEmpty());
        assertTrue(index.search(null, 10).isEmpty());
        assertTrue(index.search("   ", 10).isEmpty());
    }

    @Test
    void 查询含QueryParser特殊字符不炸() {
        seed();

        // : ^ " AND OR 这些是 QueryParser 语法字符，escape 后按字面词处理
        List<TextHit> hits = index.search("红塔:AND\"test^", 10);

        // 不断言命中数（分词结果取决于 SmartCN），只断言：不抛异常、结构合法
        assertNotNull(hits);
    }

    @Test
    void 重启后索引续用() {
        seed();
        index.shutdown(); // 模拟进程退出

        // CREATE_OR_APPEND 模式：同一目录重开，历史索引仍可查（与 SQLite 同一持久化口径）
        LuceneFullTextIndex restarted = new LuceneFullTextIndex(tempDir.resolve("lucene").toString());
        try {
            assertEquals(2, restarted.search("红塔", 10).size());
        } finally {
            restarted.shutdown();
        }
    }

    private List<TextHit> hitContaining(List<TextHit> hits, String documentId) {
        List<TextHit> result = new java.util.ArrayList<>();
        for (TextHit hit : hits) {
            if (hit.getDocumentId().equals(documentId)) {
                result.add(hit);
            }
        }
        return result;
    }
}
