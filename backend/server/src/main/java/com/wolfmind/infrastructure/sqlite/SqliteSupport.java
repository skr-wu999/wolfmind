package com.wolfmind.infrastructure.sqlite;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 连接与结构初始化。
 *
 * <p>PRAGMA 取值来自 database-schema.md §12.1：WAL 让归档写入不阻塞查询读取，
 * {@code busy_timeout} 避免 WAL 下仍是单写者时立即失败。{@code foreign_keys} 是逐连接生效的，
 * 因此每条连接都要设置，不能只在初始化时执行一次。
 *
 * <p>连接策略：归档只有少量写入者，用一条专用写连接串行提交，批大小可控，
 * 也避免多条写连接争抢唯一的写锁。
 */
public final class SqliteSupport {

    /** 事件归档单批上限，database-schema.md §10.1（同 system-design.md §5）。 */
    public static final int MAX_BATCH_SIZE = 200;

    /** 本实现自带的初始结构版本，与 {@code schema.sql} 的 v1 建表集合对应。 */
    static final int INITIAL_SCHEMA_VERSION = 1;

    /**
     * Phase 5 追加的结构版本：{@code agent_memory}（AI 分析的决策痕迹归档）。
     *
     * <p>新增表用 {@code CREATE TABLE IF NOT EXISTS} 表达，对已有库也是安全的，因此不需要
     * 独立迁移脚本；这里补一条版本记录，是为了让"这个库被谁改过"能从 {@code schema_version}
     * 查出来，而不是只能靠读代码。
     */
    static final int AGENT_MEMORY_SCHEMA_VERSION = 2;

    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private SqliteSupport() {}

    /** 打开一条已配置 PRAGMA 的连接。 */
    public static Connection open(String jdbcUrl) throws SQLException {
        ensureDriverRegistered();
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        Connection connection = DriverManager.getConnection(jdbcUrl, config.toProperties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA wal_autocheckpoint = 1000");
        }
        return connection;
    }

    /**
     * 显式注册 SQLite JDBC 驱动。
     *
     * <p>驱动自带 {@code META-INF/services}，正常情况下 {@link DriverManager} 会自动发现它；
     * 但在某些类加载器场景（容器、模块路径、被裁剪的运行时）自动发现会失败，
     * 表现为 {@code No suitable driver found}。本模块只连 SQLite 一种数据库，
     * 显式注册没有副作用，却能让失败原因从"驱动找不到"变成真正的连接错误。
     */
    private static void ensureDriverRegistered() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("缺少 sqlite-jdbc 依赖：org.sqlite.JDBC 不在类路径上", error);
        }
    }

    /**
     * 应用 {@code /db/schema.sql}。
     *
     * <p>脚本里的语句都是 {@code CREATE ... IF NOT EXISTS}，因此重复启动是安全的；这里刻意不做
     * "迁移框架"：结构演进按 database-schema.md §4 由发布流程手工 {@code ALTER TABLE}
     * 并补一条 {@code schema_version} 记录。
     *
     * <p>只有"结构版本 1"这一行由代码写入，因为初始结构就是本实现自带的。写入用真实毫秒时间，
     * 而不是脚本里写死的常量——否则 {@code applied_at} 就不再表示"何时被应用"。
     */
    static void applySchema(Connection connection) throws SQLException, IOException {
        for (String statement : splitStatements(readSchemaScript())) {
            try (Statement jdbcStatement = connection.createStatement()) {
                jdbcStatement.execute(statement);
            }
        }
        recordSchemaVersion(connection, INITIAL_SCHEMA_VERSION,
                "初始结构：schema_version / users / games / game_events"
                        + "（database-schema.md §3.2 §3.3 §3.4）");
        recordSchemaVersion(connection, AGENT_MEMORY_SCHEMA_VERSION,
                "agent_memory：AI 分析的决策痕迹归档（database-schema.md §3.6，Phase 5）");
        recordSchemaVersion(connection, 3, "game_history/statistics：可靠事件消费的索引与统计");
    }

    /** 记录一条结构版本；已存在则不动（{@code applied_at} 表示首次应用时间）。 */
    private static void recordSchemaVersion(Connection connection, int version, String note)
            throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO schema_version (version, applied_at, note)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, version);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, note);
            statement.executeUpdate();
        }
    }

    private static String readSchemaScript() throws IOException {
        try (InputStream stream = SqliteSupport.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IOException("找不到结构脚本资源：" + SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 按分号切分 SQL 脚本，并去掉 {@code --} 行注释。
     *
     * <p>为什么是"够用的切分"而不是完整 SQL 解析器：脚本由本仓库维护，只含 DDL，
     * 没有字符串字面量里的分号，也没有触发器体。真需要复杂脚本时应该换成
     * SQLite 的多语句 API 或逐条调用，而不是在这里堆解析器。
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String stripped = line;
            int comment = stripped.indexOf("--");
            if (comment >= 0) {
                stripped = stripped.substring(0, comment);
            }
            if (stripped.isBlank()) {
                continue;
            }
            current.append(stripped).append('\n');
            if (stripped.trim().endsWith(";")) {
                String statement = current.toString().trim();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }
}
