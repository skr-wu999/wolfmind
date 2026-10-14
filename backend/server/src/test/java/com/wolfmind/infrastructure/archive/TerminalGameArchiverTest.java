package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository.ArchivedEvent;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository.GameRecord;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalGameArchiverTest {

    private static final String FINISHED = "g_archive_done";
    private static final String RUNNING = "g_archive_running";
    private static final String SECOND = "g_archive_second";

    private InfrastructureGameHarness harness;
    private ControlledTurnDriver driver;
    private SqliteArchiveRepository archive;
    private final List<String> shortened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new InfrastructureGameHarness(1_730_000_000_000L);
        AgentViewAdapter views = new AgentViewAdapter(harness.viewQuery());
        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace, null, harness.time());
        AIControllerAdapter adapter = new AIControllerAdapter(runtime, views, gateway, harness.time(),
                AgentConfig.ruleBaseline("archive-it"));
        driver = new ControlledTurnDriver(harness.repository(), adapter, harness.time(),
                harness.publisher(), harness.eventStore(), harness.lock());

        playToEnd(FINISHED, 20240501L);
        harness.startNineAiGame(RUNNING, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 7L);

        archive = SqliteArchiveRepository.inMemory();
    }

    private void playToEnd(String gameId, long seed) {
        harness.startNineAiGame(gameId, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, seed);
        for (int step = 0; step < 500 && status(gameId) == GameStatus.RUNNING; step++) {
            harness.advanceClock(200L);
            driver.step(gameId);
        }
        assertEquals(GameStatus.FINISHED, status(gameId), "夹具必须打完整局");
    }

    @AfterEach
    void tearDown() {
        archive.close();
    }

    private GameStatus status(String gameId) {
        return harness.repository().load(gameId).game().snapshot().status();
    }

    private TerminalGameArchiver archiver(SqliteArchiveRepository target) {
        return new TerminalGameArchiver(harness.viewQuery(), harness.eventStore(), target, shortened::add, harness.time());
    }

    @Test
    void finishedGameIsArchivedOnceAndRetentionShortenedOnlyAfterTheGamesRow() {
        List<EventEnvelope> stream = harness.eventStream(FINISHED);
        int version = stream.size();

        assertEquals(1, archiver(archive).sweep());

        assertEquals(version, archive.archivedCount(FINISHED));
        assertTrue(archive.versionGaps(FINISHED).isEmpty());
        assertEquals(0, archive.archivedCount(RUNNING), "进行中的对局不归档");
        GameRecord row = archive.game(FINISHED).orElseThrow();
        assertEquals("FINISHED", row.status());
        assertEquals(version, row.version());
        assertEquals(version, row.eventsArchived());
        assertEquals(9, row.playerCount());
        assertEquals(stream.get(version - 1).payload().get("winner"), row.winner());
        assertEquals(stream.get(0).occurredAt(), row.startedAt());
        assertEquals(stream.get(version - 1).occurredAt(), row.finishedAt());
        assertTrue(row.roleCompositionJson().contains("WEREWOLF"), row.roleCompositionJson());
        assertEquals(List.of(FINISHED), shortened);
        List<EventEnvelope> readBack = archive.readRange(FINISHED, 1, version);
        assertEquals(version, readBack.size());
        for (int i = 0; i < version; i++) {
            assertEquals(asJson(stream.get(i)), readBack.get(i), "第 " + (i + 1) + " 条归档读回不等");
        }
    }

    /** 事件流里的载荷仍是 Java 原生类型，归档读回的是 JSON 解码结果；比较前按同一编码归一。 */
    @SuppressWarnings("unchecked")
    private static EventEnvelope asJson(EventEnvelope event) {
        Object payload = Json.narrowIntegers(Json.read(Json.write(event.payload())));
        return new EventEnvelope(event.eventId(), event.gameId(), event.version(), event.type(),
                event.schemaVersion(), event.occurredAt(), event.phase(), event.round(), event.actorId(),
                event.visibility(), event.causationId(), event.correlationId(), (Map<String, Object>) payload);
    }

    @Test
    void twoFinishedGamesBothArchiveCompletely() {
        playToEnd(SECOND, 7L);

        assertEquals(2, archiver(archive).sweep());

        for (String gameId : List.of(FINISHED, SECOND)) {
            int version = harness.eventStream(gameId).size();
            assertEquals(version, archive.archivedCount(gameId), gameId + " 的事件不能被另一局的同号 eventId 吞掉");
            assertTrue(archive.game(gameId).orElseThrow().archivedThrough(version));
        }
        assertEquals(List.of(FINISHED, SECOND), shortened.stream().sorted().toList());
    }

    @Test
    void repeatedSweepsDoNotRewriteOrReshorten() {
        TerminalGameArchiver archiver = archiver(archive);
        archiver.sweep();
        assertEquals(0, archiver.sweep());
        assertEquals(0, archiver.sweep());
        assertEquals(List.of(FINISHED), shortened);
        assertEquals(0, archive.duplicateEventIdGroups());
    }

    @Test
    void sqliteFailureLeavesRetentionUntouchedAndNextSweepRetries() {
        SqliteArchiveRepository broken = SqliteArchiveRepository.inMemory();
        broken.close();

        assertEquals(0, archiver(broken).sweep(), "失败不能抛出调度线程，也不能算作完成");
        assertTrue(shortened.isEmpty(), "SQLite 未提交时不能缩短 Redis 保留期");

        assertEquals(1, archiver(archive).sweep());
        assertEquals(List.of(FINISHED), shortened);
    }

    @Test
    void partiallyArchivedGameIsCompletedWithoutDuplicates() {
        List<EventEnvelope> stream = harness.eventStream(FINISHED);
        archive.archive(stream.subList(0, 50).stream().map(e -> ArchivedEvent.of(e, 1L)).toList());
        assertTrue(archive.game(FINISHED).isEmpty(), "只写了部分事件，还没有完成标记");

        assertEquals(1, archiver(archive).sweep());
        assertEquals(stream.size(), archive.archivedCount(FINISHED));
        assertEquals(0, archive.duplicateEventIdGroups());
    }

    @Test
    void restartBackfillReappliesRetentionForAlreadyArchivedGames() {
        archiver(archive).sweep();
        shortened.clear();

        assertEquals(0, archiver(archive).sweep(), "新进程不重复写入已完成的归档");
        assertEquals(List.of(FINISHED), shortened, "上次进程可能在写完 games 行后、缩短 TTL 前退出");
    }

    @Test
    void failedRetentionUpdateReportsBacklogAndRetriesTheCompletedArchive() {
        var metrics = new com.wolfmind.infrastructure.metrics.InProcessMetrics();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var worker = new TerminalGameArchiver(harness.viewQuery(), harness.eventStore(), archive, id -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("retention update interrupted");
            shortened.add(id);
        }, harness.time(), metrics);
        assertEquals(0, worker.sweep());
        assertEquals(1L, metrics.snapshot().gauges().get("wolfmind.archive.pending.games"));
        assertEquals(1L, metrics.snapshot().gauges().get("wolfmind.archive.failed.games"));
        assertEquals(1L, metrics.snapshot().counters().get("wolfmind.archive.failures"));
        assertTrue(archive.game(FINISHED).orElseThrow().archivedThrough(harness.eventStream(FINISHED).size()));
        assertEquals(0, worker.sweep());
        assertEquals(0L, metrics.snapshot().gauges().get("wolfmind.archive.pending.games"));
        assertEquals(0L, metrics.snapshot().gauges().get("wolfmind.archive.failed.games"));
        assertEquals(List.of(FINISHED), shortened);
    }
}
