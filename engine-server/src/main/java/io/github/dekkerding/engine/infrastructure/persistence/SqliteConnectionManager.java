package io.github.dekkerding.engine.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接管理器 —— 持久层的地基。
 *
 * <p>【选型理由】（修复蓝本"纯内存零持久化"缺陷）
 * 嵌入式单文件数据库：无独立服务进程、无网络、备份=复制文件、SQL 可查可教学。
 * xerial/sqlite-jdbc 内嵌各平台原生 dll，Windows 下零配置。
 *
 * <p>【教学注释 · 每次操作新连接 vs 连接池】
 * SQLite 是文件级数据库（没有 MySQL 那种网络握手成本），开关连接很便宜；
 * WAL 模式支持一写多读。学习规模下"每次借还"最简单可靠，不引入连接池复杂度。
 * 真到高并发写再考虑单写连接队列——那时再演进，YAGNI。
 */
@Component
public class SqliteConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SqliteConnectionManager.class);

    private final String databasePath;

    public SqliteConnectionManager(@Value("${engine.persistence.sqlite-path:data/engine.db}") String databasePath) {
        this.databasePath = databasePath;
    }

    /** 初始化：建目录 + 建库 + 打开 WAL。Spring 生命周期里由 DatabaseMigrator 触发 */
    public void initialize() {
        try {
            Path parent = Paths.get(databasePath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // WAL（Write-Ahead Logging）：读写不互相阻塞，规避 Windows 文件锁的读写冲突
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            log.info("SQLite 就绪: {} (WAL 模式)", Paths.get(databasePath).toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 初始化失败: " + databasePath, e);
        }
    }

    /** 打开一个新连接（调用方负责 try-with-resources 关闭） */
    public Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }
}
