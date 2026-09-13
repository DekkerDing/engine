package io.github.dekkerding.engine.infrastructure.fulltext;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.model.search.TextHit;
import io.github.dekkerding.engine.domain.repository.FullTextIndex;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene 全文索引实现 —— FullTextIndex 端口的技术落地。
 *
 * <p>【教学注释 · Lucene 的"倒排索引"一图流】
 * <pre>
 *   正排（文档 → 词）:  块1[红塔, 公园, 小花]   块2[宝塔, 红色]
 *   倒排（词 → 文档）:  红塔 → [块1]  塔类 → ...  公园 → [块1]  小花 → [块1]
 *   查询"红塔" = 查倒排表拿候选块 → BM25 打分（词频↑、块长↓、词稀有度↑ 得分高）→ 排序
 * </pre>
 * SQL 的 LIKE '%红塔%' 是全表逐行扫描且无法用索引；倒排表是 O(命中数)——这就是全文引擎的存在意义。
 *
 * <p>【SmartCN】中文没有空格分隔，必须先分词。"红塔与小花" → [红塔, 与, 小花]；
 * 若用 StandardAnalyzer 会退化成单字切分 [红][塔][与][小][花]，"红的花"也能命中"红塔"，
 * 检索质量崩坏——蓝本项目正是注释掉了 analyzers-common 依赖踩了这个坑。
 *
 * <p>【生命周期】IndexWriter 全局单例（Lucene 设计为多线程安全），构造时打开、
 * 容器销毁时 @PreDestroy 关闭；DirectoryReader 每次检索新开（NRT 近实时读，
 * 能看到 writer 已提交内容），用完即还。
 */
@Component
public class LuceneFullTextIndex implements FullTextIndex {

    private static final Logger log = LoggerFactory.getLogger(LuceneFullTextIndex.class);

    /** 高亮标记：与前端约定 <em> 标签，前端按 HTML 渲染加粗 */
    private static final String HIGHLIGHT_PRE = "<em>";
    private static final String HIGHLIGHT_POST = "</em>";

    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final IndexWriter writer;
    private final QueryParser parser;

    public LuceneFullTextIndex(
            @Value("${engine.lucene.index-path:lucene-index}") String indexPath) {
        try {
            FSDirectory directory = FSDirectory.open(Paths.get(indexPath));
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            // CREATE_OR_APPEND：首启建索引，重启续写（与 SQLite 的"数据在 data/ 下不丢"同一口径）
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.writer = new IndexWriter(directory, config);
            this.parser = new QueryParser("text", analyzer);
            log.info("Lucene 全文索引就绪: {} (SmartCN 分词)", Paths.get(indexPath).toAbsolutePath());
        } catch (IOException e) {
            throw EngineException.internal("Lucene 索引初始化失败: " + indexPath, e);
        }
    }

    @Override
    public void indexDocument(String documentId, List<TextChunk> chunks) {
        try {
            // 先清后写 = 幂等（重传/重试不产生重复块），与 VectorStore.save 同一口径
            writer.deleteDocuments(new Term("docId", documentId));
            for (TextChunk chunk : chunks) {
                Document doc = new Document();
                // StringField 不分词、整串精确匹配——专供按文档删除用（Term 删除走的就是它）
                doc.add(new StringField("docId", documentId, Field.Store.YES));
                // 数字存成字符串：chunkIndex 只用于展示对齐，不需要 Lucene 层的范围查询
                doc.add(new StringField("chunkIndex", String.valueOf(chunk.getChunkIndex()), Field.Store.YES));
                // TextField 分词 + 存原文：分词给检索，原文给高亮与展示
                doc.add(new TextField("text", chunk.getText(), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
            log.info("全文索引写入: {} ({} 块)", documentId, chunks.size());
        } catch (IOException e) {
            // 摄取链路的任何一步失败都必须让文档进 FAILED——这里抛领域异常由应用层接住
            throw EngineException.internal("全文索引写入失败: " + documentId, e);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        try {
            writer.deleteDocuments(new Term("docId", documentId));
            writer.commit();
            log.info("全文索引删除: {}", documentId);
        } catch (IOException e) {
            throw EngineException.internal("全文索引删除失败: " + documentId, e);
        }
    }

    @Override
    public List<TextHit> search(String query, int topK) {
        if (query == null || query.trim().isEmpty() || topK <= 0) {
            return new ArrayList<>();
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // 【安全细节】QueryParser 有自己的语法（AND/OR/:/^/" 等操作符），
            // 用户输入原样 parse 会语法报错甚至被构造恶意复杂查询——先 escape 成纯词项
            Query parsed = parser.parse(QueryParser.escape(query.trim()));
            TopDocs top = searcher.search(parsed, topK);

            // 高亮器：对命中词项包 <em> 标签（QueryScorer 按查询词找文本偏移，取最佳片段）
            Highlighter highlighter = new Highlighter(
                    new SimpleHTMLFormatter(HIGHLIGHT_PRE, HIGHLIGHT_POST), new QueryScorer(parsed));

            List<TextHit> hits = new ArrayList<>(Math.min(topK, top.scoreDocs.length));
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                String text = doc.get("text");
                hits.add(new TextHit(
                        doc.get("docId"),
                        Integer.parseInt(doc.get("chunkIndex")),
                        text,
                        scoreDoc.score,
                        bestFragment(highlighter, text)));
            }
            return hits;
        } catch (Exception e) {
            throw EngineException.internal("全文检索失败: " + query, e);
        }
    }

    /** 最佳高亮片段；无命中词项时返回 null（展示层截断原文兜底） */
    private String bestFragment(Highlighter highlighter, String text) {
        try {
            return highlighter.getBestFragment(analyzer, "text", text);
        } catch (Exception e) {
            // 高亮失败不应让整个检索失败——降级为无高亮
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            writer.close();
            log.info("Lucene 索引已关闭");
        } catch (IOException e) {
            log.warn("Lucene 索引关闭异常（数据已 commit，不影响完整性）", e);
        }
    }
}
