package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.vector.VectorEntry;
import io.github.dekkerding.engine.domain.model.vector.VectorHit;
import io.github.dekkerding.engine.domain.repository.VectorStore;
import io.github.dekkerding.engine.infrastructure.search.InMemoryVectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 向量库的 SQLite 实现 —— 双写结构：SQLite 是真相库，内存索引是检索工作集。
 *
 * <p>【数据流】
 * <pre>
 *   摄取: save() ──事务──> SQLite(先清后写) ──同步──> InMemoryVectorIndex.replace()
 *   检索: search() ──────> InMemoryVectorIndex（不打磁盘）
 *   启动: preload() ─────> SELECT 全量 → 重建内存索引（重启不丢检索能力）
 *   删除: deleteByDocument() → SQLite 删行 + index.remove()
 * </pre>
 *
 * <p>【教学注释 · 为什么检索不走 SQL】向量相似度没有"等于"可言，SQL 的 WHERE
 * 表达不了"余弦最大的 K 条"——要么把全表拉回内存算（等于绕一圈），要么用扩展
 * （sqlite-vec）。既然横竖都是内存扫，就诚实地把工作集放在内存里，SQLite 只管持久化。
 *
 * <p>【一致性口径】写路径是"先库后内存"：库写失败时内存不会被动过（不会出现
 * 内存有、库里没有的幽灵数据）；库写成功后内存更新在同一把锁语义下由
 * {@link InMemoryVectorIndex} 的 synchronized 保证原子替换。
 */
@Component
public class SqliteVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteVectorStore.class);

    private final SqliteConnectionManager connectionManager;
    private final InMemoryVectorIndex index = new InMemoryVectorIndex();

    public SqliteVectorStore(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * 启动预加载：把库里全部向量条目重建进内存索引。
     * 【启动顺序】@Order(5)：晚于 DatabaseMigrator 的 @Order(1)——表结构必须先就位。
     * （老库升级场景 V2 的 degraded 列就是迁移补出来的，先读会直接 SQLException）
     */
    @Order(5)
    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        String sql = "SELECT document_id, source_type, chunk_index, text, model_key," +
                " dim, vector, degraded, created_at FROM vector_entries";
        int count = 0;
        Map<String, List<VectorEntry>> byDocument = new TreeMap<>();
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                VectorEntry entry = mapRow(rs);
                byDocument.computeIfAbsent(entry.getDocumentId(), k -> new ArrayList<>()).add(entry);
                count++;
            }
        } catch (SQLException e) {
            throw EngineException.internal("向量库预加载失败", e);
        }
        for (Map.Entry<String, List<VectorEntry>> e : byDocument.entrySet()) {
            index.replace(e.getKey(), e.getValue());
        }
        if (count > 0) {
            log.info("向量索引预加载完成: {} 条向量, {} 个文档（重启后检索能力无损恢复）", count, byDocument.size());
        } else {
            log.info("向量库为空：跳过预加载（首次启动或尚未摄取文档）");
        }
    }

    @Override
    public void save(String documentId, List<VectorEntry> entries) {
        // 先清后写 = 幂等：同一文档重复摄取（重试/重传）不会留下旧块向量
        String deleteSql = "DELETE FROM vector_entries WHERE document_id=?";
        String insertSql = "INSERT INTO vector_entries(document_id, source_type, chunk_index," +
                " text, model_key, dim, vector, degraded, created_at) VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connectionManager.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(deleteSql);
                 PreparedStatement insert = connection.prepareStatement(insertSql)) {
                delete.setString(1, documentId);
                delete.executeUpdate();

                for (VectorEntry entry : entries) {
                    insert.setString(1, entry.getDocumentId());
                    insert.setString(2, entry.getSourceType());
                    insert.setInt(3, entry.getChunkIndex());
                    insert.setString(4, entry.getText());
                    insert.setString(5, entry.getModelKey());
                    insert.setInt(6, entry.getDimension());
                    insert.setBytes(7, VectorBlobCodec.encode(entry.getVector()));
                    insert.setInt(8, entry.isDegraded() ? 1 : 0);
                    insert.setString(9, entry.getCreatedAt().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw EngineException.internal("向量写入失败: " + documentId, e);
        }
        // 库成功后再动内存（见类注释的一致性口径）
        index.replace(documentId, entries);
    }

    @Override
    public List<VectorHit> search(float[] queryVector, int topK, String sourceType, String modelKey) {
        return index.search(queryVector, topK, sourceType, modelKey);
    }

    @Override
    public void deleteByDocument(String documentId) {
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM vector_entries WHERE document_id=?")) {
            ps.setString(1, documentId);
            int deleted = ps.executeUpdate();
            log.info("向量清理完成: {} (deleted={} 条)", documentId, deleted);
        } catch (SQLException e) {
            throw EngineException.internal("向量删除失败: " + documentId, e);
        }
        index.remove(documentId);
    }

    @Override
    public long count() {
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM vector_entries");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw EngineException.internal("向量计数失败", e);
        }
    }

    private VectorEntry mapRow(ResultSet rs) throws SQLException {
        int dim = rs.getInt("dim");
        return new VectorEntry(
                rs.getString("document_id"),
                rs.getString("source_type"),
                rs.getInt("chunk_index"),
                rs.getString("text"),
                rs.getString("model_key"),
                rs.getInt("degraded") == 1,
                VectorBlobCodec.decode(rs.getBytes("vector"), dim),
                Instant.parse(rs.getString("created_at")));
    }
}
