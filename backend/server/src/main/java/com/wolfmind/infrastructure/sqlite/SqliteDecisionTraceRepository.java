package com.wolfmind.infrastructure.sqlite;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.DecisionTraceArchivePort;
import com.wolfmind.infrastructure.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * {@code agent_memory} 表中 {@code memory_type='DECISION_TRACE'} 行的唯一写入者与读取者。
 *
 * <p><b>幂等键的来源。</b>database-schema.md §3.6 用 {@code (game_id, agent_id, source_event_id)}
 * 做唯一索引，而轨迹事件**没有 eventId**——它是 {@code game:{id}:agent:{playerId}} 流上的内部记录，
 * 不参与事件信封。因此 {@code source_event_id} 取"轨道的结构指纹"：
 * {@code sha1(type|version|occurredAt|phase|round|payloadJson)}。
 *
 * <p>它是**内容寻址**而不是位置寻址，这是刻意的：同一回合的同一轨迹事件重复投递会算出同一个键
 * 而被唯一索引忽略（满足 §3.6 的幂等要求），而两条语义不同的事件不会因为"序号相同"互相覆盖。
 * 代价是：payload 完全相同的两条事件会被视为同一条。实际不可能发生——
 * 时间戳粒度是毫秒，而一次回合内的轨迹事件间隔远大于此。
 *
 * <p><b>只接受 {@code AGENT_} 前缀。</b>这里再判一次类型前缀，而不是信任调用方：
 * 这个类一旦接受了领域事件，{@code agent_memory} 就会变成"对局事件的第二份副本"，
 * 而 §8.3 明确表态决策痕迹与 {@code game_events} 无交集是刻意隔离。
 */
public final class SqliteDecisionTraceRepository implements DecisionTraceArchivePort, AutoCloseable {

    /** 决策痕迹的固定维度取值（§6.2）。 */
    private static final String SCOPE_GAME = "GAME";
    private static final String TYPE_DECISION_TRACE = "DECISION_TRACE";

    /**
     * 决策痕迹的可读性权重。
     *
     * <p>§6.1 给的是"回合结论 > 策略决策 > 上下文"的分层。这个值只用于排序，
     * 不参与胜负判定，因此可以按类型给出固定权重，不需要模型参与。
     */
    private static final double IMPORTANCE_DEFAULT = 0.5;

    private static final String INSERT_TRACE = """
            INSERT OR IGNORE INTO agent_memory
              (agent_id, player_id, user_id, game_id, scope, memory_type, role, phase, round, version,
               visibility, content, keywords, importance, source_event_id, source_type,
               embedding, embedding_model, occurred_at, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final Connection connection;

    /** 以内存库打开（测试与本地演示）。 */
    public static SqliteDecisionTraceRepository inMemory() {
        return new SqliteDecisionTraceRepository("jdbc:sqlite::memory:");
    }

    public SqliteDecisionTraceRepository(String jdbcUrl) {
        try {
            this.connection = SqliteSupport.open(jdbcUrl);
            SqliteSupport.applySchema(connection);
        } catch (SQLException | IOException error) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "打开或初始化 SQLite 决策痕迹库失败：" + error.getMessage(), error);
        }
    }

    /**
     * 投票阶段多个座位在不同线程同时写入，而事务状态挂在唯一的连接上：
     * 不串行化时一个线程的 {@code BEGIN} / {@code COMMIT} / {@code ROLLBACK} 会作用到另一个线程的写入。
     */
    @Override
    public synchronized boolean archive(AgentTracePort.AgentTraceEvent event) {
        if (event == null) {
            return false;
        }
        if (!event.wireType().startsWith("AGENT_")) {
            throw new IllegalArgumentException(
                    "决策痕迹只归档 AGENT_ 前缀事件，实际为 " + event.wireType()
                            + "（database-schema.md §8.3：轨迹与 game_events 无交集是刻意隔离）");
        }
        String content = Json.write(event.payload());
        try {
            try (Statement begin = connection.createStatement()) {
                begin.execute("BEGIN IMMEDIATE");
            }
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_TRACE)) {
                statement.setString(1, event.playerId());
                statement.setString(2, event.playerId());
                statement.setNull(3, java.sql.Types.VARCHAR);
                statement.setString(4, event.gameId());
                statement.setString(5, SCOPE_GAME);
                statement.setString(6, TYPE_DECISION_TRACE);
                statement.setNull(7, java.sql.Types.VARCHAR);
                statement.setString(8, event.phase());
                statement.setInt(9, event.round());
                statement.setInt(10, event.version());
                // 决策痕迹不进入可见性矩阵：它由授权后的分析接口读取，不是对局事件。
                // 写 NULL 而不是 PUBLIC，避免"看起来可以被广播"。
                statement.setNull(11, java.sql.Types.VARCHAR);
                statement.setString(12, content);
                statement.setString(13, keywordsOf(event));
                statement.setDouble(14, importanceOf(event));
                statement.setString(15, fingerprint(event, content));
                statement.setString(16, event.wireType());
                statement.setNull(17, java.sql.Types.BLOB);
                statement.setNull(18, java.sql.Types.VARCHAR);
                statement.setLong(19, event.occurredAt());
                statement.setLong(20, System.currentTimeMillis());
                inserted = statement.executeUpdate();
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("COMMIT");
            }
            return inserted > 0;
        } catch (SQLException error) {
            rollbackQuietly();
            throw new SqliteArchiveRepository.ArchiveException(
                    "归档决策痕迹失败（重投递是安全的）：" + error.getMessage(), error);
        }
    }

    @Override
    public synchronized List<AgentTracePort.AgentTraceEvent> load(String gameId, String playerId) {
        String sql = """
                SELECT source_type, agent_id, game_id, version, phase, round, occurred_at, content
                FROM agent_memory
                WHERE game_id = ? AND memory_type = 'DECISION_TRACE'
                  AND (? IS NULL OR agent_id = ?)
                ORDER BY version, occurred_at, memory_id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gameId);
            if (playerId == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, playerId);
                statement.setString(3, playerId);
            }
            List<AgentTracePort.AgentTraceEvent> events = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(toTraceEvent(rows));
                }
            }
            return List.copyOf(events);
        } catch (SQLException error) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "读取决策痕迹失败：" + error.getMessage(), error);
        }
    }

    @Override
    public synchronized int count(String gameId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM agent_memory WHERE game_id = ? AND memory_type = 'DECISION_TRACE'")) {
            statement.setString(1, gameId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException error) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "统计决策痕迹失败：" + error.getMessage(), error);
        }
    }

    /** 预留列巡检：{@code embedding} 必须恒为 NULL（§6.6、OP-D10）。 */
    public synchronized int reservedColumnsUsed() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM agent_memory WHERE embedding IS NOT NULL"
                             + " OR embedding_model IS NOT NULL")) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException error) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "预留列巡检失败：" + error.getMessage(), error);
        }
    }

    @SuppressWarnings("unchecked")
    private static AgentTracePort.AgentTraceEvent toTraceEvent(ResultSet rows) throws SQLException {
        AgentTracePort.TraceEventType type;
        try {
            type = AgentTracePort.TraceEventType.valueOf(rows.getString("source_type"));
        } catch (IllegalArgumentException error) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "归档中存在未知轨迹类型：" + rows.getString("source_type"), error);
        }
        // 整数收窄：JSON 没有整数宽度，读取器统一给 Long，而轨迹 payload 在写入侧用 Integer
        // （promptTokens / latencyMs / llmCalls 等）。不收窄的话，读回的 payload 与原 payload
        // 不再 equals——那会让"归档前后逐字段相等"这类断言无谓地失败，也会让分析接口
        // 对同一个字段在不同来源下给出不同类型。与 SqliteArchiveRepository 走同一条规则。
        Object payload = Json.narrowIntegers(Json.read(rows.getString("content")));
        if (!(payload instanceof Map<?, ?> map)) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "决策痕迹 content 不是 JSON 对象（database-schema.md §3.6 要求单行 JSON）", null);
        }
        return new AgentTracePort.AgentTraceEvent(
                type,
                rows.getString("game_id"),
                rows.getString("agent_id"),
                rows.getInt("version"),
                rows.getString("phase"),
                rows.getInt("round"),
                rows.getLong("occurred_at"),
                (Map<String, Object>) map);
    }

    /**
     * 关键词：只放"可结构化检索"的维度，不把 content 分词。
     *
     * <p>§6.5 的检索按结构化条件加关键词排序，而关键词需要落库。这里给的是**从 payload 直接
     * 映射出来的枚举词**（工具名、决策结论、选择动作），因此不会引入分词器这个新组件，
     * 也不会因为分词把敏感片段索引出去。
     */
    private static String keywordsOf(AgentTracePort.AgentTraceEvent event) {
        List<String> keywords = new ArrayList<>();
        keywords.add(event.wireType());
        keywords.add(event.phase());
        for (String key : List.of("tool", "decision", "chosen", "model", "provider", "denialCode", "stage")) {
            Object value = event.payload().get(key);
            if (value instanceof String text && !text.isBlank()) {
                keywords.add(text);
            }
        }
        return Json.write(keywords);
    }

    private static double importanceOf(AgentTracePort.AgentTraceEvent event) {
        // 回合结论与失败比"上下文装配"更值得看：这一层排序完全由代码决定，不调用模型。
        return switch (event.type()) {
            case AGENT_TURN_FINISHED, AGENT_TURN_FAILED, AGENT_TURN_TIMEOUT -> 0.9;
            case AGENT_OUTPUT_REJECTED, AGENT_TOOL_CALLED -> 0.7;
            case AGENT_STRATEGY_DECIDED, AGENT_LLM_INVOKED -> 0.6;
            case AGENT_TURN_STARTED, AGENT_CONTEXT_BUILT -> IMPORTANCE_DEFAULT;
        };
    }

    private static String fingerprint(AgentTracePort.AgentTraceEvent event, String contentJson) {
        String material = event.wireType() + '|' + event.playerId() + '|' + event.version() + '|'
                + event.phase() + '|' + event.round() + '|' + event.occurredAt() + '|' + contentJson;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "dt_" + HexFormat.of().formatHex(
                    digest.digest(material.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行时不支持 SHA-256，无法为决策痕迹生成幂等键", error);
        }
    }

    private void rollbackQuietly() {
        try (Statement rollback = connection.createStatement()) {
            rollback.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // 连接已损坏时回滚也会失败，原始异常才是要报出去的那个。
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException error) {
            throw new SqliteArchiveRepository.ArchiveException(
                    "关闭 SQLite 决策痕迹库失败：" + error.getMessage(), error);
        }
    }
}
