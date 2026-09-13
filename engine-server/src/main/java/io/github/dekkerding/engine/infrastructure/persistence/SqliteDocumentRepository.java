package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.document.Document;
import io.github.dekkerding.engine.domain.model.document.DocumentStatus;
import io.github.dekkerding.engine.domain.model.document.TextChunk;
import io.github.dekkerding.engine.domain.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文档仓储的 SQLite 实现 —— domain/repository/DocumentRepository 接口的技术落地。
 *
 * <p>【教学注释 · JDBC 手写 SQL 的价值】先手写你才知道 ORM 帮你做了什么
 * （参数绑定、结果映射、事务）。本项目规模小、SQL 少，手写比引 MyBatis 更清晰。
 *
 * <p>【安全铁律】所有带用户输入的 SQL 一律 PreparedStatement 参数绑定，
 * 字符串拼接 SQL = SQL 注入，没有例外。
 */
@Component
public class SqliteDocumentRepository implements DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteDocumentRepository.class);

    private final SqliteConnectionManager connectionManager;

    public SqliteDocumentRepository(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void save(Document document) {
        String sql = "INSERT INTO documents(id, filename, stored_path, file_size, status, chunk_count," +
                " vectorized_count, degraded, error_message, created_at, updated_at)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             PreparedStatement ps = holder.connection.prepareStatement(sql)) {
            ps.setString(1, document.getId());
            ps.setString(2, document.getFilename());
            ps.setString(3, document.getStoredPath());
            ps.setLong(4, document.getFileSizeBytes());
            ps.setString(5, document.getStatus().name());
            ps.setInt(6, document.getChunkCount());
            ps.setInt(7, document.getVectorizedCount());
            ps.setInt(8, document.isDegraded() ? 1 : 0);
            ps.setString(9, document.getErrorMessage());
            ps.setString(10, document.getCreatedAt().toString());
            ps.setString(11, document.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw EngineException.internal("文档保存失败: " + document.getId(), e);
        }
    }

    @Override
    public void update(Document document) {
        String sql = "UPDATE documents SET status=?, chunk_count=?, vectorized_count=?," +
                " degraded=?, error_message=?, updated_at=? WHERE id=?";
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             PreparedStatement ps = holder.connection.prepareStatement(sql)) {
            ps.setString(1, document.getStatus().name());
            ps.setInt(2, document.getChunkCount());
            ps.setInt(3, document.getVectorizedCount());
            ps.setInt(4, document.isDegraded() ? 1 : 0);
            ps.setString(5, document.getErrorMessage());
            ps.setString(6, Instant.now().toString());
            ps.setString(7, document.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw EngineException.internal("文档更新失败: " + document.getId(), e);
        }
    }

    @Override
    public Optional<Document> findById(String id) {
        String sql = "SELECT * FROM documents WHERE id=?";
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             PreparedStatement ps = holder.connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Document>empty();
            }
        } catch (SQLException e) {
            throw EngineException.internal("文档查询失败: " + id, e);
        }
    }

    @Override
    public List<Document> findAll() {
        String sql = "SELECT * FROM documents ORDER BY created_at DESC";
        List<Document> result = new ArrayList<>();
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             Statement statement = holder.connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw EngineException.internal("文档列表查询失败", e);
        }
    }

    @Override
    public void deleteById(String id) {
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager)) {
            // 级联清理：分块 + 向量（外键级联只对 chunks/vector_entries 生效于同连接事务）
            holder.connection.setAutoCommit(false);
            try (PreparedStatement delChunks = holder.connection.prepareStatement(
                    "DELETE FROM chunks WHERE document_id=?");
                 PreparedStatement delVectors = holder.connection.prepareStatement(
                    "DELETE FROM vector_entries WHERE document_id=?");
                 PreparedStatement delDoc = holder.connection.prepareStatement(
                    "DELETE FROM documents WHERE id=?")) {
                delChunks.setString(1, id);
                delChunks.executeUpdate();
                delVectors.setString(1, id);
                delVectors.executeUpdate();
                delDoc.setString(1, id);
                int deleted = delDoc.executeUpdate();
                holder.connection.commit();
                log.info("文档删除完成: {} (deleted={})", id, deleted);
            } catch (SQLException e) {
                holder.connection.rollback();
                throw e;
            } finally {
                holder.connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw EngineException.internal("文档删除失败: " + id, e);
        }
    }

    @Override
    public void saveChunks(String documentId, List<TextChunk> chunks) {
        String sql = "INSERT OR REPLACE INTO chunks(document_id, chunk_index, text, created_at) VALUES(?,?,?,?)";
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             PreparedStatement ps = holder.connection.prepareStatement(sql)) {
            for (TextChunk chunk : chunks) {
                ps.setString(1, documentId);
                ps.setInt(2, chunk.getChunkIndex());
                ps.setString(3, chunk.getText());
                ps.setString(4, Instant.now().toString());
                ps.addBatch(); // 批量：一条条 execute 远慢于一次提交一批
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw EngineException.internal("分块保存失败: " + documentId, e);
        }
    }

    @Override
    public List<TextChunk> findChunks(String documentId) {
        String sql = "SELECT chunk_index, text FROM chunks WHERE document_id=? ORDER BY chunk_index";
        List<TextChunk> result = new ArrayList<>();
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             PreparedStatement ps = holder.connection.prepareStatement(sql)) {
            ps.setString(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TextChunk(documentId, rs.getInt(1), rs.getString(2)));
                }
            }
            return result;
        } catch (SQLException e) {
            throw EngineException.internal("分块查询失败: " + documentId, e);
        }
    }

    @Override
    public void deleteChunks(String documentId) {
        try (ConnectionHolder holder = ConnectionHolder.of(connectionManager);
             PreparedStatement ps = holder.connection.prepareStatement(
                     "DELETE FROM chunks WHERE document_id=?")) {
            ps.setString(1, documentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw EngineException.internal("分块删除失败: " + documentId, e);
        }
    }

    // ---------- 行 → 领域对象映射（字段顺序与 SELECT * 的表定义一致） ----------

    private Document mapRow(ResultSet rs) throws SQLException {
        Document document = new Document(
                rs.getString("id"), rs.getString("filename"), rs.getString("stored_path"),
                rs.getLong("file_size"),
                Instant.parse(rs.getString("created_at")));
        // 重建历史状态：绕过状态机（库里的状态是既成事实），见 Document.restoreStatus 注释
        document.restoreStatus(DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("error_message"));
        document.recordChunks(rs.getInt("chunk_count"));
        document.restoreProgress(rs.getInt("vectorized_count"), rs.getInt("degraded") == 1);
        return document;
    }

    /**
     * 连接持有器：让 try-with-resources 统一管理借还（Connection 本身就是 AutoCloseable，
     * 包装是为了 future 扩展（如事务边界管理）且让借还点在代码里显式可见）。
     */
    static class ConnectionHolder implements AutoCloseable {
        final Connection connection;

        private ConnectionHolder(Connection connection) {
            this.connection = connection;
        }

        static ConnectionHolder of(SqliteConnectionManager manager) throws SQLException {
            return new ConnectionHolder(manager.open());
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
