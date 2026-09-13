package io.github.dekkerding.engine.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dekkerding.engine.domain.exception.EngineException;
import io.github.dekkerding.engine.domain.model.image.ImageAsset;
import io.github.dekkerding.engine.domain.model.image.ImageStatus;
import io.github.dekkerding.engine.domain.repository.ImageAssetRepository;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 图片资产仓储的 SQLite 实现 —— V5 image_asset 表（V7 起含语义标注列）的读写。
 *
 * <p>与 SqliteDocumentRepository 同构（安全铁律：参数绑定、无字符串拼接 SQL）；
 * 删除时联动清理 vector_entries 里的图片行（document_id = 图片 ID），
 * 与文档删除的级联事务同一模式。
 *
 * <p>【tags 列的 JSON 编解码】tags 是词表（无固定结构），以 JSON 数组字符串
 * 存 TEXT 列；编解码失败不炸查询——退化为空列表并告警（标注标签的展示价值
 * 不值得让整个列表接口失败）。
 */
@Component
public class SqliteImageAssetRepository implements ImageAssetRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteImageAssetRepository.class);
    private static final ObjectMapper TAG_MAPPER = new ObjectMapper();

    private final SqliteConnectionManager connectionManager;

    public SqliteImageAssetRepository(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void save(ImageAsset imageAsset) {
        String sql = "INSERT INTO image_asset(id, filename, stored_path, file_size, status," +
                " degraded, error_message, subject, description, tags, annotation_mocked, annotation_error," +
                " created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, imageAsset.getId());
            ps.setString(2, imageAsset.getFilename());
            ps.setString(3, imageAsset.getStoredPath());
            ps.setLong(4, imageAsset.getFileSizeBytes());
            ps.setString(5, imageAsset.getStatus().name());
            ps.setInt(6, imageAsset.isDegraded() ? 1 : 0);
            ps.setString(7, imageAsset.getErrorMessage());
            ps.setString(8, imageAsset.getSubject());
            ps.setString(9, imageAsset.getDescription());
            ps.setString(10, encodeTags(imageAsset.getTags()));
            if (imageAsset.getAnnotationMocked() == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setInt(11, imageAsset.getAnnotationMocked() ? 1 : 0);
            }
            ps.setString(12, imageAsset.getAnnotationError());
            ps.setString(13, imageAsset.getCreatedAt().toString());
            ps.setString(14, imageAsset.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw EngineException.internal("图片保存失败: " + imageAsset.getId(), e);
        }
    }

    @Override
    public void update(ImageAsset imageAsset) {
        String sql = "UPDATE image_asset SET status=?, degraded=?, error_message=?, updated_at=?," +
                " subject=?, description=?, tags=?, annotation_mocked=?, annotation_error=?" +
                " WHERE id=?";
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, imageAsset.getStatus().name());
            ps.setInt(2, imageAsset.isDegraded() ? 1 : 0);
            ps.setString(3, imageAsset.getErrorMessage());
            ps.setString(4, Instant.now().toString());
            ps.setString(5, imageAsset.getSubject());
            ps.setString(6, imageAsset.getDescription());
            ps.setString(7, encodeTags(imageAsset.getTags()));
            if (imageAsset.getAnnotationMocked() == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, imageAsset.getAnnotationMocked() ? 1 : 0);
            }
            ps.setString(9, imageAsset.getAnnotationError());
            ps.setString(10, imageAsset.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw EngineException.internal("图片更新失败: " + imageAsset.getId(), e);
        }
    }

    @Override
    public Optional<ImageAsset> findById(String id) {
        String sql = "SELECT * FROM image_asset WHERE id=?";
        try (Connection connection = connectionManager.open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.<ImageAsset>empty();
            }
        } catch (SQLException e) {
            throw EngineException.internal("图片查询失败: " + id, e);
        }
    }

    @Override
    public List<ImageAsset> findAll() {
        String sql = "SELECT * FROM image_asset ORDER BY created_at DESC";
        List<ImageAsset> result = new ArrayList<>();
        try (Connection connection = connectionManager.open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw EngineException.internal("图片列表查询失败", e);
        }
    }

    @Override
    public void deleteById(String id) {
        try (Connection connection = connectionManager.open()) {
            // 级联清理：向量行（source_type='image' 且 document_id=图片 ID）与资产行同一事务
            connection.setAutoCommit(false);
            try (PreparedStatement delVectors = connection.prepareStatement(
                    "DELETE FROM vector_entries WHERE document_id=? AND source_type='image'");
                 PreparedStatement delAsset = connection.prepareStatement(
                    "DELETE FROM image_asset WHERE id=?")) {
                delVectors.setString(1, id);
                delVectors.executeUpdate();
                delAsset.setString(1, id);
                delAsset.executeUpdate();
                connection.commit();
                log.info("图片删除完成: {}", id);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw EngineException.internal("图片删除失败: " + id, e);
        }
    }

    // ---------- 行 → 领域对象映射 ----------

    private ImageAsset mapRow(ResultSet rs) throws SQLException {
        ImageAsset imageAsset = new ImageAsset(
                rs.getString("id"), rs.getString("filename"), rs.getString("stored_path"),
                rs.getLong("file_size"),
                Instant.parse(rs.getString("created_at")));
        // 重建历史状态：绕过状态机（库里的状态是既成事实），见 ImageAsset.restoreState 注释
        imageAsset.restoreState(ImageStatus.valueOf(rs.getString("status")),
                rs.getInt("degraded") == 1, rs.getString("error_message"));
        // 重建语义标注（V7 列；老库迁移后默认值 = 未拆分态）
        int mockedRaw = rs.getInt("annotation_mocked");
        boolean mockedWasNull = rs.wasNull();
        imageAsset.restoreAnnotation(
                rs.getString("subject"), rs.getString("description"),
                decodeTags(rs.getString("tags")),
                mockedWasNull ? null : (mockedRaw == 1),
                rs.getString("annotation_error"));
        return imageAsset;
    }

    private static String encodeTags(List<String> tags) {
        try {
            return TAG_MAPPER.writeValueAsString(tags == null ? Collections.emptyList() : tags);
        } catch (Exception e) {
            log.warn("tags JSON 编码失败，退化为空列表: {}", e.getMessage());
            return "[]";
        }
    }

    private static List<String> decodeTags(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return TAG_MAPPER.readValue(json, TAG_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("tags JSON 解码失败（{}），退化为空列表", e.getMessage());
            return Collections.emptyList();
        }
    }
}

