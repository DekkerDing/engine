package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.model.requirement.Attachment;
import io.github.dekkerding.engine.domain.repository.AttachmentRepository;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 附件仓储实现 —— attachment 元数据行（文件本体在 LocalFileAttachmentStore）。
 */
@Component
public class SqliteAttachmentRepository implements AttachmentRepository {

    private final SqliteConnectionManager connectionManager;

    public SqliteAttachmentRepository(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public Attachment insert(Attachment attachment) {
        String sql = "INSERT INTO attachment" +
                " (requirement_id, file_name, stored_path, size_bytes, content_type, created_at)" +
                " VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, attachment.getRequirementId());
            ps.setString(2, attachment.getFileName());
            ps.setString(3, attachment.getStoredPath());
            ps.setLong(4, attachment.getSizeBytes());
            ps.setString(5, attachment.getContentType());
            ps.setString(6, attachment.getCreatedAt().toString());
            ps.executeUpdate();
            // xerial 未实现 PreparedStatement.getGeneratedKeys()，同连接查 last_insert_rowid()
            long id = -1L;
            try (ResultSet keys = connection.createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new Attachment(id, attachment.getRequirementId(), attachment.getFileName(),
                    attachment.getStoredPath(), attachment.getSizeBytes(),
                    attachment.getContentType(), attachment.getCreatedAt());
        } catch (SQLException e) {
            throw new IllegalStateException("附件写入失败: " + attachment.getFileName(), e);
        }
    }

    @Override
    public List<Attachment> findByRequirement(String requirementId) {
        String sql = "SELECT * FROM attachment WHERE requirement_id = ? ORDER BY id";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requirementId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Attachment> all = new ArrayList<Attachment>();
                while (rs.next()) {
                    all.add(mapRow(rs));
                }
                return all;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("附件列表查询失败: " + requirementId, e);
        }
    }

    @Override
    public Optional<Attachment> findById(long id) {
        String sql = "SELECT * FROM attachment WHERE id = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Attachment>empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("附件查询失败: " + id, e);
        }
    }

    @Override
    public Optional<Attachment> findById(String requirementId, long id) {
        String sql = "SELECT * FROM attachment WHERE id = ? AND requirement_id = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, requirementId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<Attachment>empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("附件查询失败: " + id, e);
        }
    }

    private Attachment mapRow(ResultSet rs) throws SQLException {
        return new Attachment(
                rs.getLong("id"),
                rs.getString("requirement_id"),
                rs.getString("file_name"),
                rs.getString("stored_path"),
                rs.getLong("size_bytes"),
                rs.getString("content_type"),
                Instant.parse(rs.getString("created_at")));
    }
}
