package io.github.dekkerding.engine.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库迁移器 —— 建表/升级的唯一入口（应用启动时执行）。
 *
 * <p>【教学注释 · schema_version 简易迁移】真实项目用 Flyway/Liquibase；
 * 这里手写最小实现让你看清迁移的本质：有序的 DDL 脚本 + 已执行记录表。
 * 新增表结构就加 V2/V3... 并 append 到 SCRIPTS 数组，老库自动补跑，新库一次建齐。
 */
@Component
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private final SqliteConnectionManager connectionManager;

    /** V1：初始三张表 */
    private static final String[] V1 = {
            // 文档表：一行一个上传文件（聚合根）
            "CREATE TABLE IF NOT EXISTS documents (" +
                    " id TEXT PRIMARY KEY," +
                    " filename TEXT NOT NULL," +
                    " stored_path TEXT NOT NULL," +
                    " status TEXT NOT NULL," +
                    " chunk_count INTEGER NOT NULL DEFAULT 0," +
                    " error_message TEXT," +
                    " created_at TEXT NOT NULL," +
                    " updated_at TEXT NOT NULL)",
            // 分块表：一行一个语义段（文档的从属实体）
            "CREATE TABLE IF NOT EXISTS chunks (" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " document_id TEXT NOT NULL," +
                    " chunk_index INTEGER NOT NULL," +
                    " text TEXT NOT NULL," +
                    " created_at TEXT NOT NULL," +
                    " UNIQUE(document_id, chunk_index)," +
                    " FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)",
            // 向量表：一行一条向量（BLOB 存 float32 二进制，text 冗余避免检索时 JOIN）
            // source_type 预留 image；model_key 预留 clip —— 图片扩展零 DDL
            "CREATE TABLE IF NOT EXISTS vector_entries (" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " document_id TEXT NOT NULL," +
                    " source_type TEXT NOT NULL DEFAULT 'text'," +
                    " chunk_index INTEGER NOT NULL," +
                    " text TEXT NOT NULL," +
                    " model_key TEXT NOT NULL," +
                    " dim INTEGER NOT NULL," +
                    " vector BLOB NOT NULL," +
                    " created_at TEXT NOT NULL," +
                    " FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)",
            // 检索最常见路径：按文档删/查
            "CREATE INDEX IF NOT EXISTS idx_chunks_document ON chunks(document_id)",
            "CREATE INDEX IF NOT EXISTS idx_vectors_document ON vector_entries(document_id)",
            "CREATE INDEX IF NOT EXISTS idx_vectors_source ON vector_entries(source_type)",
    };

    /** V2：向量表补降级标志列（老库 ALTER 补列，新库随 V1 建表后立即补跑，一次到位） */
    private static final String[] V2 = {
            // SQLite 允许 ADD COLUMN 带 NOT NULL，前提是有非空 DEFAULT——老行自动填 0
            "ALTER TABLE vector_entries ADD COLUMN degraded INTEGER NOT NULL DEFAULT 0",
    };

    /** V3：文档表补进度与降级列（摄取进度轮询、降级标记的落库载体） */
    private static final String[] V3 = {
            "ALTER TABLE documents ADD COLUMN vectorized_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE documents ADD COLUMN degraded INTEGER NOT NULL DEFAULT 0",
    };

    /** V4：文档表补文件大小列（spec 文档管理：列表需展示大小；老行默认 0） */
    private static final String[] V4 = {
            "ALTER TABLE documents ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * V5：图片资产表（图片跨模态检索）。结构与 documents 同构但更轻——
     * 图片"一图一向量"（无 chunk_count/进度列），向量行复用 vector_entries
     * （source_type='image'，V1 建表时预留的列此刻兑现，零改动）。
     */
    private static final String[] V5 = {
            "CREATE TABLE IF NOT EXISTS image_asset (" +
                    " id TEXT PRIMARY KEY," +
                    " filename TEXT NOT NULL," +
                    " stored_path TEXT NOT NULL," +
                    " file_size INTEGER NOT NULL DEFAULT 0," +
                    " status TEXT NOT NULL," +
                    " degraded INTEGER NOT NULL DEFAULT 0," +
                    " error_message TEXT," +
                    " created_at TEXT NOT NULL," +
                    " updated_at TEXT NOT NULL)",
    };

    /**
     * V7：图片资产表补语义标注五列（photo-semantic-search）。
     * 存量行默认值 = "未拆分"（subject/description 空串、annotation_mocked 空 NULL）
     * ——三态纪律：未拆分（NULL）≠ mock 产出（1）≠ 真实 VLM 产出（0）。
     * tags 以 JSON 数组字符串存储（词表无固定结构，JSON 自描述最稳）。
     */
    private static final String[] V7 = {
            "ALTER TABLE image_asset ADD COLUMN subject TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE image_asset ADD COLUMN description TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE image_asset ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE image_asset ADD COLUMN annotation_mocked INTEGER",
            "ALTER TABLE image_asset ADD COLUMN annotation_error TEXT",
    };

    /**
     * V6：向量空间键纠错的占位（非 DDL，见 fixVectorSpaceKeys）。
     * 【序列纪律】V6 必须在数组里占位：版本号 = 数组下标 + 1，漏占位会让
     * 后续数组版本与已落库的版本号错位（V7 会顶替 6，再撞硬编码分支的 UNIQUE）。
     */
    private static final String[] V6 = {};

    /**
     * V8：需求工厂四表（requirement-forge 变更）。
     * requirement_submission 是聚合根；md_artifact 一个版本 = 一个渲染目标的
     * 多文件产物（files_json 存 {相对路径: 内容} 映射，openspec 包多文件、
     * vibecoding 单文件，结构统一便于 zip 直接遍历）。
     * 【三列语义】template_output_json 不可变（确定性断言基准）；revised_json
     * 可空（人工修订，整体覆盖不合并）；files_json 是当前有效内容
     * （渲染时 = 模板输出，保存修订时 = 修订内容）——读侧只看它。
     * form_template 首版内置一条固定记录（表单引擎演进预留，见 design D2）。
     */
    private static final String[] V8 = {
            "CREATE TABLE IF NOT EXISTS form_template (" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " name TEXT NOT NULL," +
                    " version INTEGER NOT NULL," +
                    " schema_json TEXT NOT NULL," +
                    " is_active INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS requirement_submission (" +
                    " id TEXT PRIMARY KEY," +
                    " title TEXT NOT NULL DEFAULT ''," +
                    " capability_slug TEXT NOT NULL DEFAULT ''," +
                    " form_data_json TEXT NOT NULL," +
                    " status TEXT NOT NULL," +
                    " created_at TEXT NOT NULL," +
                    " updated_at TEXT NOT NULL," +
                    " submitted_at TEXT," +
                    " exported_at TEXT)",
            "CREATE TABLE IF NOT EXISTS md_artifact (" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " requirement_id TEXT NOT NULL," +
                    " target TEXT NOT NULL," +
                    " version INTEGER NOT NULL," +
                    " files_json TEXT NOT NULL," +
                    " template_output_json TEXT NOT NULL," +
                    " revised_json TEXT," +
                    " is_stale INTEGER NOT NULL DEFAULT 0," +
                    " template_version TEXT NOT NULL," +
                    " created_at TEXT NOT NULL," +
                    " UNIQUE(requirement_id, target, version)," +
                    " FOREIGN KEY(requirement_id) REFERENCES requirement_submission(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS attachment (" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " requirement_id TEXT NOT NULL," +
                    " file_name TEXT NOT NULL," +
                    " stored_path TEXT NOT NULL," +
                    " size_bytes INTEGER NOT NULL," +
                    " content_type TEXT NOT NULL," +
                    " created_at TEXT NOT NULL," +
                    " FOREIGN KEY(requirement_id) REFERENCES requirement_submission(id) ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS idx_requirements_status ON requirement_submission(status)",
            "CREATE INDEX IF NOT EXISTS idx_artifacts_requirement ON md_artifact(requirement_id)",
            "CREATE INDEX IF NOT EXISTS idx_attachments_requirement ON attachment(requirement_id)",
            // 种子：首版表单结构固定（三步表单的字段清单，schema_json 仅作描述性元数据）
            "INSERT INTO form_template(name, version, schema_json, is_active) VALUES(" +
                    " '需求提交表单', 1, '{\"steps\":[\"基本信息\",\"业务描述\",\"验收与约束\"]}', 1)",
    };

    /** 迁移脚本版本序列：只增不改（改历史脚本 = 破坏老库升级） */
    private static final String[][] SCRIPTS = {V1, V2, V3, V4, V5, V6, V7, V8};

    /** 当前文本引擎配置键（V6 纠错的目标空间键） */
    private final String textModelKey;

    public DatabaseMigrator(SqliteConnectionManager connectionManager,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${engine.python.model-key:text-embedding-zh}") String textModelKey) {
        this.connectionManager = connectionManager;
        this.textModelKey = textModelKey;
    }

    /**
     * 【启动顺序】@Order(1)：迁移必须先于其它 ApplicationReady 监听者
     * （SqliteVectorStore 的内存预加载 @Order(5) 依赖表结构已就位）。
     */
    @org.springframework.core.annotation.Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        connectionManager.initialize();
        try (Connection connection = connectionManager.open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (" +
                    " version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            int current = currentVersion(statement);

            for (int version = current; version < SCRIPTS.length; version++) {
                log.info("应用数据库迁移 V{} ...", version + 1);
                if (version == 5) {
                    // V6：数据纠错（非 DDL，见 fixVectorSpaceKeys 注释）
                    fixVectorSpaceKeys(statement);
                } else {
                    for (String ddl : SCRIPTS[version]) {
                        statement.execute(ddl);
                    }
                }
                statement.executeUpdate("INSERT INTO schema_version(version, applied_at) VALUES(" +
                        (version + 1) + ", datetime('now'))");
            }
            log.info("数据库迁移完成（当前版本 V{}）", SCRIPTS.length);
        } catch (SQLException e) {
            throw new IllegalStateException("数据库迁移失败", e);
        }
    }

    /**
     * V6：向量空间键纠错 —— 历史缺陷的存量数据修复。
     *
     * <p>【缺陷回顾】修复前的 ChannelEmbeddingProvider 把 Python 返回的 model_name
     * （如 "BAAI/bge-small-zh-v1.5"）写进 VectorEntry.modelKey，降级时甚至把整段
     * 加载错误消息（"hash-fallback(OSError: ...)"）当键写入。空间键本应是配置键
     * （与检索侧 provider.modelKey() 对齐）——检索按配置键过滤，错键存量全部失配，
     * 空间闸门按设计拦截（"不可比"）。
     *
     * <p>【为什么可以刷】键错了但<b>向量本身没错</b>：同一模型同一空间，只是身份证
     * 姓名栏填错——更正身份不改变数学。只刷两类已知污染键，且仅当当前配置就是
     * 默认文本引擎时执行（用户若改配了其它模型，这两类默认引擎的旧键不该被并入）。
     */
    private void fixVectorSpaceKeys(Statement statement) throws SQLException {
        if (!"text-embedding-zh".equals(textModelKey)) {
            log.info("V6 跳过：当前文本引擎非默认配置（{}），不做默认引擎的存量键纠错", textModelKey);
            return;
        }
        int fixedNames = statement.executeUpdate("UPDATE vector_entries SET model_key='" + textModelKey + "'" +
                " WHERE source_type='text' AND model_key='BAAI/bge-small-zh-v1.5'");
        int fixedDegraded = statement.executeUpdate("UPDATE vector_entries SET model_key='" + textModelKey + "'" +
                " WHERE source_type='text' AND model_key LIKE 'hash-fallback%'");
        log.info("V6 空间键纠错完成：模型名键 {} 行 + 降级污染键 {} 行 → {}",
                fixedNames, fixedDegraded, textModelKey);
    }

    private int currentVersion(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
