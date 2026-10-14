package com.wolfmind.infrastructure.sqlite;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.infrastructure.json.Json;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 事件归档仓储：{@code game_events} 的唯一写入者（database-schema.md §8.1 的 cg-projection 分支）。
 *
 * <p>两条不可违反的语义：
 * <ol>
 *   <li><b>单批上限 200</b>：超过则拆成多个事务顺序提交，避免长事务阻塞其他写入
 *       （database-schema.md §10.1）。批次拆分是仓储的责任，调用方只管交一整个列表。</li>
 *   <li><b>幂等重放</b>：写入语句固定为 {@code INSERT OR IGNORE}，靠
 *       {@code ux_game_events_event_id} 与 {@code ux_game_events_game_version} 两条唯一索引去重。
 *       重复投递影响行数为 0 属于正常现象，不报错、不计入失败。
 *       <b>不允许先 SELECT 再 INSERT</b>：那样在并发下会撞唯一约束报错，而且把
 *       "去重"从数据库挪到了应用层，失去唯一索引的兜底价值（AP-D5）。</li>
 * </ol>
 *
 * <p>归档是真相副本，不是真相：本类不参与任何对局状态判定，也不回写事件流。
 */
public final class SqliteArchiveRepository implements AutoCloseable {

    /** 事件归档单批上限；与 database-schema.md §10.1 一致。 */
    public static final int MAX_BATCH_SIZE = SqliteSupport.MAX_BATCH_SIZE;

    private static final String INSERT_EVENT = """
            INSERT OR IGNORE INTO game_events
              (event_id, game_id, version, type, schema_version, occurred_at, phase, round,
               actor_id, visibility, causation_id, correlation_id, payload, entry_id, archived_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final Connection connection;

    /** 以内存库打开（测试与本地演示）。 */
    public static SqliteArchiveRepository inMemory() {
        return new SqliteArchiveRepository("jdbc:sqlite::memory:");
    }

    public SqliteArchiveRepository(String jdbcUrl) {
        try {
            this.connection = SqliteSupport.open(jdbcUrl);
            SqliteSupport.applySchema(connection);
        } catch (SQLException | IOException error) {
            throw new ArchiveException("打开或初始化 SQLite 归档库失败：" + error.getMessage(), error);
        }
    }

    /**
     * 归档一批事件。
     *
     * @param events 待归档事件；顺序即写入顺序，超过 {@link #MAX_BATCH_SIZE} 时自动分批
     * @return 每个实际提交批次的结果，顺序与批次顺序一致
     */
    public synchronized List<BatchResult> archive(List<ArchivedEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<BatchResult> results = new ArrayList<>();
        for (int from = 0; from < events.size(); from += MAX_BATCH_SIZE) {
            int to = Math.min(from + MAX_BATCH_SIZE, events.size());
            results.add(archiveBatch(events.subList(from, to)));
        }
        return List.copyOf(results);
    }

    /** 一次事务提交一批事件。 */
    private BatchResult archiveBatch(List<ArchivedEvent> batch) {
        int inserted = 0;
        try {
            // BEGIN IMMEDIATE 而不是 setAutoCommit(false)：前者在语句开始时就拿写锁，
            // 后者是延迟事务，第一次写入时才升级锁，锁升级失败会直接报 SQLITE_BUSY
            // （database-schema.md §12.2 明确要求 BEGIN IMMEDIATE）。
            // 注意两者只能选一个：autoCommit=false 之后 JDBC 会自己发 BEGIN，
            // 再执行 BEGIN IMMEDIATE 就是"事务里开事务"而报错。
            try (Statement begin = connection.createStatement()) {
                begin.execute("BEGIN IMMEDIATE");
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
                for (ArchivedEvent event : batch) {
                    bind(statement, event);
                    inserted += statement.executeUpdate();
                }
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("COMMIT");
            }
        } catch (SQLException error) {
            rollbackQuietly();
            throw new ArchiveException("归档批次写入失败（整批重做是安全的）：" + error.getMessage(), error);
        }
        return new BatchResult(batch.size(), inserted, batch.size() - inserted);
    }

    private static void bind(PreparedStatement statement, ArchivedEvent event) throws SQLException {
        EventEnvelope envelope = event.envelope();
        statement.setString(1, envelope.eventId());
        statement.setString(2, envelope.gameId());
        statement.setInt(3, envelope.version());
        statement.setString(4, envelope.type());
        statement.setInt(5, envelope.schemaVersion());
        statement.setLong(6, envelope.occurredAt());
        statement.setString(7, envelope.phase());
        statement.setInt(8, envelope.round());
        statement.setString(9, envelope.actorId());
        statement.setString(10, envelope.visibility().name());
        statement.setString(11, envelope.causationId());
        statement.setString(12, envelope.correlationId());
        statement.setString(13, Json.write(envelope.payload()));
        statement.setString(14, event.entryId());
        statement.setLong(15, event.archivedAt());
    }

    /**
     * 写入或更新 {@code games} 行。它是归档的完成标记，必须在事件全部写入且核对通过之后调用：
     * {@code version} 与 {@code events_archived} 都等于流尾版本才算归档完成。
     */
    public synchronized void upsertGame(GameRecord game, long updatedAt) {
        String sql = """
                INSERT INTO games
                  (game_id, room_id, status, phase, round, version, player_count, role_composition,
                   winner, finish_reason, started_at, finished_at, events_archived, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(game_id) DO UPDATE SET
                  status = excluded.status, phase = excluded.phase, round = excluded.round,
                  version = excluded.version, winner = excluded.winner,
                  finish_reason = excluded.finish_reason, finished_at = excluded.finished_at,
                  events_archived = excluded.events_archived, updated_at = excluded.updated_at
                """;
        try {
            try (Statement begin = connection.createStatement()) {
                begin.execute("BEGIN IMMEDIATE");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, game.gameId());
                statement.setString(2, game.roomId());
                statement.setString(3, game.status());
                statement.setString(4, game.phase());
                statement.setInt(5, game.round());
                statement.setInt(6, game.version());
                statement.setInt(7, game.playerCount());
                statement.setString(8, game.roleCompositionJson());
                statement.setString(9, game.winner());
                statement.setString(10, game.finishReason());
                statement.setLong(11, game.startedAt());
                if (game.finishedAt() == null) {
                    statement.setNull(12, java.sql.Types.INTEGER);
                } else {
                    statement.setLong(12, game.finishedAt());
                }
                statement.setInt(13, game.eventsArchived());
                statement.setLong(14, updatedAt);
                statement.executeUpdate();
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("COMMIT");
            }
        } catch (SQLException error) {
            rollbackQuietly();
            throw new ArchiveException("写入 games 行失败：" + error.getMessage(), error);
        }
    }

    public synchronized List<String> completedGameIds() {
        var ids = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT game_id FROM games WHERE status IN ('FINISHED','ABORTED') AND version=events_archived AND version>0 ORDER BY game_id")) {
            while(rows.next()) ids.add(rows.getString(1));
            return List.copyOf(ids);
        } catch(SQLException error) { throw new ArchiveException("List completed games failed",error); }
    }

    /** 读取 {@code games} 行；不存在表示从未完成过归档。 */
    public synchronized Optional<GameRecord> game(String gameId) {
        String sql = """
                SELECT game_id, room_id, status, phase, round, version, player_count, role_composition,
                       winner, finish_reason, started_at, finished_at, events_archived
                FROM games WHERE game_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gameId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                long finishedAt = rows.getLong("finished_at");
                Long finished = rows.wasNull() ? null : finishedAt;
                return Optional.of(new GameRecord(
                        rows.getString("game_id"), rows.getString("room_id"), rows.getString("status"),
                        rows.getString("phase"), rows.getInt("round"), rows.getInt("version"),
                        rows.getInt("player_count"), rows.getString("role_composition"),
                        rows.getString("winner"), rows.getString("finish_reason"),
                        rows.getLong("started_at"), finished, rows.getInt("events_archived")));
            }
        } catch (SQLException error) {
            throw new ArchiveException("读取 games 行失败：" + error.getMessage(), error);
        }
    }

    /** 某对局已归档的版本号，升序。 */
    public List<Integer> archivedVersions(String gameId) {
        List<Integer> versions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM game_events WHERE game_id = ? ORDER BY version")) {
            statement.setString(1, gameId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    versions.add(rows.getInt(1));
                }
            }
        } catch (SQLException error) {
            throw new ArchiveException("读取归档版本失败：" + error.getMessage(), error);
        }
        return List.copyOf(versions);
    }

    /** 某对局已归档的事件条数；与 {@code games.events_archived} 对账用。 */
    public synchronized int archivedCount(String gameId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM game_events WHERE game_id = ?")) {
            statement.setString(1, gameId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException error) {
            throw new ArchiveException("读取归档条数失败：" + error.getMessage(), error);
        }
    }

    /**
     * 连通性探测（`/api/v1/health` 用）。
     *
     * <p>只跑一条不依赖任何表的语句：健康检查不该因为"归档表恰好为空"而失败，
     * 也不该把一次全表扫描挂在每 15 秒一次的探针上。
     */
    public boolean ping() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next() && rows.getInt(1) == 1;
        } catch (SQLException error) {
            return false;
        }
    }

    /** 全表 {@code event_id} 重复计数；唯一索引保证恒为 0（database-schema.md OP-D04）。 */
    public int duplicateEventIdGroups() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM (SELECT event_id FROM game_events GROUP BY event_id HAVING COUNT(*) > 1)")) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException error) {
            throw new ArchiveException("重复 event_id 巡检失败：" + error.getMessage(), error);
        }
    }

    /** 版本空洞巡检，返回形如 {@code "51-53"} 的区间描述；空列表表示归档连续（OP-D03）。 */
    public synchronized List<String> versionGaps(String gameId) {
        List<String> gaps = new ArrayList<>();
        String sql = """
                SELECT version + 1 AS gap_start, next_version - 1 AS gap_end
                FROM (SELECT version, LEAD(version) OVER (ORDER BY version) AS next_version
                      FROM game_events WHERE game_id = ?)
                WHERE next_version IS NOT NULL AND next_version <> version + 1
                ORDER BY gap_start
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gameId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    gaps.add(rows.getInt(1) + "-" + rows.getInt(2));
                }
            }
        } catch (SQLException error) {
            throw new ArchiveException("版本空洞巡检失败：" + error.getMessage(), error);
        }
        return List.copyOf(gaps);
    }

    /** 按版本区间读回归档事件，供 Redis 事件流丢失后的重建（database-schema.md §10.3）。 */
    public synchronized List<EventEnvelope> readRange(String gameId, int fromVersion, int toVersion) {
        List<EventEnvelope> events = new ArrayList<>();
        String sql = """
                SELECT event_id, game_id, version, type, schema_version, occurred_at, phase, round,
                       actor_id, visibility, causation_id, correlation_id, payload
                FROM game_events
                WHERE game_id = ? AND version >= ? AND version <= ?
                ORDER BY version
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, gameId);
            statement.setInt(2, fromVersion);
            statement.setInt(3, toVersion);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(toEnvelope(rows));
                }
            }
        } catch (SQLException error) {
            throw new ArchiveException("读取归档事件失败：" + error.getMessage(), error);
        }
        return List.copyOf(events);
    }

    @SuppressWarnings("unchecked")
    private static EventEnvelope toEnvelope(ResultSet rows) throws SQLException {
        Object payload = normalizeNumbers(Json.read(rows.getString("payload")));
        return new EventEnvelope(
                rows.getString("event_id"),
                rows.getString("game_id"),
                rows.getInt("version"),
                rows.getString("type"),
                rows.getInt("schema_version"),
                rows.getLong("occurred_at"),
                rows.getString("phase"),
                rows.getInt("round"),
                rows.getString("actor_id"),
                com.wolfmind.domain.game.model.Visibility.valueOf(rows.getString("visibility")),
                rows.getString("causation_id"),
                rows.getString("correlation_id"),
                (java.util.Map<String, Object>) payload);
    }

    /** 整数收窄，规则见 {@link Json#narrowIntegers}；Redis 事件流读取走同一实现。 */
    static Object normalizeNumbers(Object value) {
        return Json.narrowIntegers(value);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException error) {
            throw new ArchiveException("关闭 SQLite 归档库失败：" + error.getMessage(), error);
        }
    }

    /** 事务由 {@code BEGIN IMMEDIATE} 手动开启，连接仍是自动提交模式，{@code Connection#rollback} 在此模式下会直接抛错。 */
    private void rollbackQuietly() {
        try (Statement rollback = connection.createStatement()) {
            rollback.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // 回滚失败只应发生在连接已损坏时，原始异常才是要报出去的那个。
        }
    }

    /** 一条待归档事件：信封 + Redis Stream 条目 ID + 归档时间。 */
    public record ArchivedEvent(EventEnvelope envelope, String entryId, long archivedAt) {

        public ArchivedEvent {
            if (envelope == null) {
                throw new IllegalArgumentException("envelope 不能为空");
            }
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("entryId 不能为空");
            }
        }

        /** 按"显式条目 ID = version"约定生成条目 ID（{@code {version}-0}）。 */
        public static ArchivedEvent of(EventEnvelope envelope, long archivedAt) {
            return new ArchivedEvent(envelope, envelope.version() + "-0", archivedAt);
        }
    }

    /** {@code games} 表的一行（database-schema.md §4）；{@code winner} 与 {@code finishedAt} 未结束时为 null。 */
    public record GameRecord(
            String gameId, String roomId, String status, String phase, int round, int version,
            int playerCount, String roleCompositionJson, String winner, String finishReason,
            long startedAt, Long finishedAt, int eventsArchived) {

        /** 终态且水位与条数都追上流尾版本。 */
        public boolean archivedThrough(int streamVersion) {
            return ("FINISHED".equals(status) || "ABORTED".equals(status))
                    && version == streamVersion
                    && eventsArchived == streamVersion;
        }
    }

    /** 一个批次的结果：{@code inserted} 是实际新增行数，{@code duplicates} 是被唯一索引忽略的行数。 */
    public record BatchResult(int total, int inserted, int duplicates) {

        /** 本批是否全部为重复投递。 */
        public boolean fullyDuplicate() {
            return total > 0 && inserted == 0;
        }
    }

    /** 归档层故障。它不属于领域拒绝，映射到 HTTP 时是 5xx。 */
    public static class ArchiveException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ArchiveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
