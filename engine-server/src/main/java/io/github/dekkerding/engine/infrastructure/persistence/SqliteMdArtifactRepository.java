package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.model.requirement.MdArtifact;
import io.github.dekkerding.engine.domain.model.requirement.RenderTarget;
import io.github.dekkerding.engine.domain.repository.MdArtifactRepository;
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
 * md 工件仓储实现 —— (requirement_id, target, version) 唯一键。
 *
 * <p>版本发号走 SELECT MAX 单连接完成（SQLite 文件级写锁天然串行化，
 * 学习规模下没有并发竞态窗口；上生产换数据库时改为 INSERT ... ON CONFLICT 重试）。
 */
@Component
public class SqliteMdArtifactRepository implements MdArtifactRepository {

    private final SqliteConnectionManager connectionManager;

    public SqliteMdArtifactRepository(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public int nextVersion(String requirementId, RenderTarget target) {
        String sql = "SELECT COALESCE(MAX(version), 0) FROM md_artifact WHERE requirement_id = ? AND target = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requirementId);
            ps.setString(2, target.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工件版本发号失败: " + requirementId, e);
        }
    }

    @Override
    public MdArtifact insert(MdArtifact artifact) {
        String sql = "INSERT INTO md_artifact" +
                " (requirement_id, target, version, files_json, template_output_json, revised_json," +
                "  is_stale, template_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, artifact.getRequirementId());
            ps.setString(2, artifact.getTarget().value());
            ps.setInt(3, artifact.getVersion());
            ps.setString(4, RequirementJson.write(artifact.getFiles()));
            ps.setString(5, RequirementJson.write(artifact.getTemplateOutput()));
            ps.setString(6, artifact.getRevised() == null ? null : RequirementJson.write(artifact.getRevised()));
            ps.setInt(7, artifact.isStale() ? 1 : 0);
            ps.setString(8, artifact.getTemplateVersion());
            ps.setString(9, artifact.getCreatedAt().toString());
            ps.executeUpdate();
            // xerial 驱动未实现 PreparedStatement.getGeneratedKeys()，用 last_insert_rowid()
            try (ResultSet keys = connection.createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) {
                    return restoreId(artifact, keys.getLong(1));
                }
            }
            return artifact;
        } catch (SQLException e) {
            throw new IllegalStateException("工件写入失败: " + artifact.getRequirementId(), e);
        }
    }

    @Override
    public void update(MdArtifact artifact) {
        String sql = "UPDATE md_artifact SET files_json = ?, revised_json = ?, is_stale = ? WHERE id = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, RequirementJson.write(artifact.getFiles()));
            ps.setString(2, artifact.getRevised() == null ? null : RequirementJson.write(artifact.getRevised()));
            ps.setInt(3, artifact.isStale() ? 1 : 0);
            ps.setLong(4, artifact.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("工件更新失败: " + artifact.getId(), e);
        }
    }

    @Override
    public List<MdArtifact> findByRequirement(String requirementId) {
        String sql = "SELECT * FROM md_artifact WHERE requirement_id = ? ORDER BY target, version";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requirementId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MdArtifact> all = new ArrayList<MdArtifact>();
                while (rs.next()) {
                    all.add(mapRow(rs));
                }
                return all;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工件列表查询失败: " + requirementId, e);
        }
    }

    @Override
    public Optional<MdArtifact> find(String requirementId, RenderTarget target, int version) {
        String sql = "SELECT * FROM md_artifact WHERE requirement_id = ? AND target = ? AND version = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requirementId);
            ps.setString(2, target.value());
            ps.setInt(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<MdArtifact>empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工件查询失败: " + requirementId, e);
        }
    }

    @Override
    public Optional<MdArtifact> findLatest(String requirementId, RenderTarget target) {
        String sql = "SELECT * FROM md_artifact WHERE requirement_id = ? AND target = ? ORDER BY version DESC LIMIT 1";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requirementId);
            ps.setString(2, target.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<MdArtifact>empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("最新工件查询失败: " + requirementId, e);
        }
    }

    // ---------- 行映射 ----------

    private MdArtifact mapRow(ResultSet rs) throws SQLException {
        MdArtifact artifact = new MdArtifact(
                rs.getLong("id"),
                rs.getString("requirement_id"),
                RenderTarget.fromValue(rs.getString("target")),
                rs.getInt("version"),
                RequirementJson.readFiles(rs.getString("template_output_json")),
                rs.getString("template_version"),
                Instant.parse(rs.getString("created_at")));
        String revisedJson = rs.getString("revised_json");
        artifact.restoreRevisionState(
                revisedJson == null ? null : RequirementJson.readFiles(revisedJson),
                RequirementJson.readFiles(rs.getString("files_json")),
                rs.getInt("is_stale") == 1);
        return artifact;
    }

    /** 重建带数据库 id 的实体（insert 拿到自增键后） */
    private MdArtifact restoreId(MdArtifact artifact, long id) {
        MdArtifact withId = new MdArtifact(id, artifact.getRequirementId(), artifact.getTarget(),
                artifact.getVersion(), artifact.getTemplateOutput(), artifact.getTemplateVersion(),
                artifact.getCreatedAt());
        withId.restoreRevisionState(artifact.getRevised(), artifact.getFiles(), artifact.isStale());
        return withId;
    }
}
