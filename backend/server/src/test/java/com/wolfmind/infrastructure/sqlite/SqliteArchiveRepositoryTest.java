package com.wolfmind.infrastructure.sqlite;

import com.wolfmind.infrastructure.InfrastructureTestEvents;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 归档：幂等重放、批量上限、唯一约束去重。
 *
 * <p>用内存库（{@code jdbc:sqlite::memory:}）而不是临时文件：唯一约束与 INSERT OR IGNORE
 * 的行为在两者上完全一致，而内存库不需要清理文件、也不会因为残留数据让测试互相影响。
 */
class SqliteArchiveRepositoryTest {

    private static final String GAME_ID = "g_archive_1";

    private SqliteArchiveRepository repository;

    @BeforeEach
    void openArchive() {
        repository = SqliteArchiveRepository.inMemory();
    }

    @AfterEach
    void closeArchive() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("schema.sql 可重复应用，核心表与唯一索引都存在")
    void schemaIsIdempotent() throws Exception {
        // 第二次打开同一个库（这里是新连接、同一个内存库不可复用，因此直接调用内部方法）
        try (var connection = SqliteSupport.open("jdbc:sqlite::memory:")) {
            SqliteSupport.applySchema(connection);
            SqliteSupport.applySchema(connection);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'ux_game_events%'")) {
                List<String> indexes = new ArrayList<>();
                while (rows.next()) {
                    indexes.add(rows.getString(1));
                }
                assertEquals(List.of("ux_game_events_event_id", "ux_game_events_game_version"), indexes.stream().sorted().toList());
            }
            // 结构版本行只写一次，且 applied_at 是真实毫秒时间（不是写死的常量）。
            // v1 = 初始结构，v2 = agent_memory（Phase 5 AI 分析的决策痕迹归档）：
            // v3 = 回放索引和统计；重复应用不会增加同一版本的行。
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT COUNT(*), MIN(applied_at), MAX(version) FROM schema_version")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1), "重复应用结构不得重复插入版本行");
                assertTrue(rows.getLong(2) > 1_600_000_000_000L, "applied_at 必须是真实毫秒时间");
                assertEquals(3, rows.getInt(3));
            }
            // agent_memory 表与它的幂等索引必须存在：AI 分析页的读数依赖它们。
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='agent_memory'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM sqlite_master WHERE type='index'"
                                 + " AND name='ux_agent_memory_trace'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "决策痕迹的幂等索引是重复投递不翻倍的唯一保证");
            }
        }
    }

    @Test
    @DisplayName("结构脚本按分号正确切分：注释被去掉，语句数与实际 DDL 一致")
    void schemaScriptSplitsStatements() {
        List<String> statements = SqliteSupport.splitStatements("""
                -- 注释行
                CREATE TABLE a (id INTEGER); -- 行尾注释
                CREATE INDEX i ON a(id);

                INSERT OR IGNORE INTO a VALUES (1);
                """);
        assertEquals(3, statements.size());
        assertEquals("CREATE TABLE a (id INTEGER)", statements.get(0));
        assertEquals("CREATE INDEX i ON a(id)", statements.get(1));
        assertTrue(statements.get(2).startsWith("INSERT OR IGNORE"));
    }

    @Test
    @DisplayName("归档写入后可按版本读回，信封契约字段与 payload 结构完全还原")
    void archivesAndReadsBackEnvelopes() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        var results = repository.archive(toArchive(events));

        assertEquals(1, results.size(), "11 条事件只需一个批次");
        assertEquals(11, results.get(0).inserted());
        assertEquals(0, results.get(0).duplicates());
        assertEquals(11, repository.archivedCount(GAME_ID));

        List<EventEnvelope> stored = repository.readRange(GAME_ID, 1, Integer.MAX_VALUE);
        assertEquals(events.size(), stored.size());
        for (int index = 0; index < events.size(); index++) {
            assertEnvelopeFaithful(events.get(index), stored.get(index));
        }
    }

    @Test
    @DisplayName("归档读回的事件能真正重放出与原事件流相同的状态")
    void archivedEventsReplayToIdenticalState() {
        // 这是归档存在的理由：Redis 事件流丢失后只靠 game_events 重建。
        // 只比较信封"看起来一样"不够——必须让领域聚合真的吃一遍。
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        repository.archive(toArchive(events));

        assertEquals(replay(events), replay(repository.readRange(GAME_ID, 1, Integer.MAX_VALUE)),
                "从归档重建的状态必须与从原事件流重建的状态逐字段相同");
    }

    @Test
    @DisplayName("同一信封重复归档不产生新行，也不改变读回内容")
    void archiveIsIdempotentOnReplay() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        repository.archive(toArchive(events));

        var replay = repository.archive(toArchive(events));

        assertEquals(11, replay.get(0).total());
        assertEquals(0, replay.get(0).inserted(), "重复投递不得新增行");
        assertEquals(11, replay.get(0).duplicates());
        assertTrue(replay.get(0).fullyDuplicate());
        assertEquals(11, repository.archivedCount(GAME_ID), "归档条数必须保持不变");
        assertEquals(0, repository.duplicateEventIdGroups(), "event_id 必须仍然唯一");
        assertEquals(11, repository.readRange(GAME_ID, 1, Integer.MAX_VALUE).size());
    }

    @Test
    @DisplayName("超出 int 范围的整数保持 Long，不静默截断")
    void largeNumbersAreNotTruncated() {
        assertEquals(List.of(1, 2_147_483_648L),
                SqliteArchiveRepository.normalizeNumbers(List.of(1L, 2_147_483_648L)));
        assertEquals(5, SqliteArchiveRepository.normalizeNumbers(5L));
        assertEquals("text", SqliteArchiveRepository.normalizeNumbers("text"));
    }

    /** 契约字段必须逐字相同；payload 允许整数宽度不同（JSON 没有宽度），但结构与数值必须一致。 */
    private static void assertEnvelopeFaithful(EventEnvelope expected, EventEnvelope actual) {
        assertEquals(expected.eventId(), actual.eventId());
        assertEquals(expected.gameId(), actual.gameId());
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.schemaVersion(), actual.schemaVersion());
        assertEquals(expected.occurredAt(), actual.occurredAt());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.round(), actual.round());
        assertEquals(expected.actorId(), actual.actorId());
        assertEquals(expected.visibility(), actual.visibility());
        assertEquals(expected.causationId(), actual.causationId());
        assertEquals(expected.correlationId(), actual.correlationId());
        assertTrue(samePayload(expected.payload(), actual.payload()),
                "payload 必须结构一致：expected=" + expected.payload() + " actual=" + actual.payload());
    }

    /**
     * 结构化的 payload 比较。
     *
     * <p>为什么不直接 {@code equals}：JSON 没有整数宽度，解码统一给 {@code Long}，而领域侧
     * 的同一个值可能是 {@code Integer}（座位号）或 {@code Long}（时限）。{@code EventCodec}
     * 用 {@code Number} 读取，两种宽度都能正确重放，因此这里比较"结构与数值"，
     * 而不是 Java 类型标签。类型敏感的比较会在归档路径上产生假告警，掩盖真正的损坏。
     */
    private static boolean samePayload(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString())) == 0;
        }
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            if (!left.keySet().equals(right.keySet())) {
                return false;
            }
            for (Object key : left.keySet()) {
                if (!samePayload(left.get(key), right.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!samePayload(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(expected, actual);
    }

    /** 用领域聚合真实重放一批事件，返回状态投影；空流返回 null。 */
    private static com.wolfmind.domain.game.model.GameSnapshot replay(List<EventEnvelope> events) {
        if (events.isEmpty()) {
            return null;
        }
        var game = com.wolfmind.domain.game.model.Game.create(
                com.wolfmind.domain.game.model.GameId.of(events.get(0).gameId()));
        for (EventEnvelope envelope : events) {
            game.replay(com.wolfmind.domain.game.event.EventCodec.decode(envelope));
        }
        return game.snapshot();
    }

    @Test
    @DisplayName("部分重复的批次只插入缺失的行（至少一次投递的常规场景）")
    void archiveInsertsOnlyMissingRows() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        repository.archive(toArchive(events.subList(0, 5)));

        var result = repository.archive(toArchive(events));

        assertEquals(11, result.get(0).total());
        assertEquals(6, result.get(0).inserted(), "只补写 6..11");
        assertEquals(5, result.get(0).duplicates());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), repository.archivedVersions(GAME_ID));
        assertTrue(repository.versionGaps(GAME_ID).isEmpty(), "补齐后不应有版本空洞");
    }

    @Test
    @DisplayName("单批上限 200：超长列表被拆成多个事务顺序提交")
    void splitsBatchesAtTwoHundred() {
        List<EventEnvelope> large = new ArrayList<>();
        for (int version = 1; version <= 450; version++) {
            large.add(InfrastructureTestEvents.bareEnvelope(GAME_ID, version, EventType.PHASE_CHANGED));
        }

        var results = repository.archive(toArchive(large));

        assertEquals(3, results.size(), "450 条应拆成 200 + 200 + 50");
        assertEquals(List.of(200, 200, 50), results.stream().map(SqliteArchiveRepository.BatchResult::total).toList());
        assertEquals(450, results.stream().mapToInt(SqliteArchiveRepository.BatchResult::inserted).sum());
        assertEquals(450, repository.archivedCount(GAME_ID));
    }

    @Test
    @DisplayName("分批失败时只回滚该批：已提交批次保留，整批重做是安全的")
    void failedBatchRollsBackOnlyThatBatch() {
        List<EventEnvelope> first = InfrastructureTestEvents.startEventStream(GAME_ID);
        repository.archive(toArchive(first));

        // 第二批含一条 gameId 与自身一致但 event_id 重复的事件（模拟重复投递中的异常输入）
        List<EventEnvelope> second = new ArrayList<>();
        second.add(InfrastructureTestEvents.envelopeWithNullPayload(GAME_ID, 12));
        second.add(first.get(0)); // event_id 与 version 都已存在

        var result = repository.archive(toArchive(second));

        assertEquals(1, result.get(0).inserted(), "只有新事件被写入");
        assertEquals(1, result.get(0).duplicates());
        assertEquals(12, repository.archivedCount(GAME_ID));
    }

    @Test
    @DisplayName("版本空洞巡检能发现并报告缺口")
    void detectsVersionGaps() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        // 只归档 1..5 与 8..11，制造 6-7 的空洞
        repository.archive(toArchive(concat(events.subList(0, 5), events.subList(7, 11))));

        assertEquals(List.of("6-7"), repository.versionGaps(GAME_ID));
    }

    @Test
    @DisplayName("AGENT_ 前缀事件不得进入游戏事件归档")
    void rejectsAgentEventTypes() {
        EventEnvelope agentEvent = InfrastructureTestEvents.bareEnvelope(GAME_ID, 1, EventType.PHASE_CHANGED);
        EventEnvelope disguised = new EventEnvelope(
                agentEvent.eventId(), agentEvent.gameId(), agentEvent.version(), "AGENT_TURN_STARTED",
                agentEvent.schemaVersion(), agentEvent.occurredAt(), agentEvent.phase(), agentEvent.round(),
                agentEvent.actorId(), agentEvent.visibility(), agentEvent.causationId(),
                agentEvent.correlationId(), agentEvent.payload());

        // EventEnvelope.eventType() 会显式拒绝未知/非目录类型，归档路径因此不可能写入 AGENT_ 事件。
        assertThrows(GameException.class, disguised::eventType);
        assertEquals(DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                assertThrows(GameException.class, disguised::eventType).code());
        assertFalse(EventType.exists("AGENT_TURN_STARTED"));
    }

    @Test
    @DisplayName("空批次是安全的 no-op")
    void emptyBatchIsNoop() {
        assertTrue(repository.archive(List.of()).isEmpty());
        assertTrue(repository.archive(null).isEmpty());
        assertEquals(0, repository.archivedCount(GAME_ID));
    }

    private static List<SqliteArchiveRepository.ArchivedEvent> toArchive(List<EventEnvelope> events) {
        return events.stream()
                .map(envelope -> SqliteArchiveRepository.ArchivedEvent.of(envelope, 1_730_000_001_000L))
                .toList();
    }

    private static List<EventEnvelope> concat(List<EventEnvelope> first, List<EventEnvelope> second) {
        List<EventEnvelope> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
