package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.model.requirement.RequirementStatus;
import io.github.dekkerding.engine.domain.model.requirement.RequirementSubmission;
import io.github.dekkerding.engine.domain.repository.RequirementRepository;
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
 * 需求仓储实现 —— requirement_submission 表的 CRUD。
 *
 * <p>form_data_json ⇄ RequirementForm 的编解码在这里完成（Jackson 不越界进 domain）。
 * Instant 以 ISO-8601 文本落库（与 documents 表同款纪律）。
 */
@Component
public class SqliteRequirementRepository implements RequirementRepository {

    private final SqliteConnectionManager connectionManager;

    public SqliteRequirementRepository(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void insert(RequirementSubmission submission) {
        String sql = "INSERT INTO requirement_submission" +
                " (id, title, capability_slug, form_data_json, status, created_at, updated_at, submitted_at, exported_at)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            bindRow(ps, submission);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("需求写入失败: " + submission.getId(), e);
        }
    }

    @Override
    public void update(RequirementSubmission submission) {
        String sql = "UPDATE requirement_submission SET" +
                " title = ?, capability_slug = ?, form_data_json = ?, status = ?," +
                " updated_at = ?, submitted_at = ?, exported_at = ? WHERE id = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, submission.getTitle());
            ps.setString(2, submission.getCapabilitySlug());
            ps.setString(3, RequirementJson.write(submission.getForm()));
            ps.setString(4, submission.getStatus().name());
            ps.setString(5, submission.getUpdatedAt().toString());
            setNullableTimestamp(ps, 6, submission.getSubmittedAt());
            setNullableTimestamp(ps, 7, submission.getExportedAt());
            ps.setString(8, submission.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("需求更新失败: " + submission.getId(), e);
        }
    }

    @Override
    public Optional<RequirementSubmission> findById(String id) {
        String sql = "SELECT * FROM requirement_submission WHERE id = ?";
        try (Connection connection = connectionManager.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<RequirementSubmission>empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("需求查询失败: " + id, e);
        }
    }

    @Override
    public List<RequirementSubmission> findAll() {
        String sql = "SELECT * FROM requirement_submission ORDER BY updated_at DESC";
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<RequirementSubmission> all = new ArrayList<RequirementSubmission>();
            while (rs.next()) {
                all.add(mapRow(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("需求列表查询失败", e);
        }
    }

    // ---------- 行映射 ----------

    private void bindRow(PreparedStatement ps, RequirementSubmission s) throws SQLException {
        ps.setString(1, s.getId());
        ps.setString(2, s.getTitle());
        ps.setString(3, s.getCapabilitySlug());
        ps.setString(4, RequirementJson.write(s.getForm()));
        ps.setString(5, s.getStatus().name());
        ps.setString(6, s.getCreatedAt().toString());
        ps.setString(7, s.getUpdatedAt().toString());
        setNullableTimestamp(ps, 8, s.getSubmittedAt());
        setNullableTimestamp(ps, 9, s.getExportedAt());
    }

    private RequirementSubmission mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        RequirementForm form = RequirementJson.read(rs.getString("form_data_json"), RequirementForm.class);
        form.ensureDefaults();
        RequirementSubmission submission = new RequirementSubmission(id, form, Instant.parse(rs.getString("created_at")));
        submission.restoreStatus(
                RequirementStatus.valueOf(rs.getString("status")),
                parseNullableTimestamp(rs, "submitted_at"),
                parseNullableTimestamp(rs, "exported_at"));
        return submission;
    }

    private void setNullableTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value.toString());
        }
    }

    private Instant parseNullableTimestamp(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Instant.parse(value);
    }
}
