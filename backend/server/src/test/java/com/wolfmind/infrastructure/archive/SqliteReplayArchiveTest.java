package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.ReplayArchivePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryEventStore;
import com.wolfmind.infrastructure.memory.InMemoryGameRepository;
import com.wolfmind.infrastructure.memory.InMemoryGameStateCache;
import com.wolfmind.infrastructure.memory.InMemoryGameViewQuery;
import com.wolfmind.infrastructure.memory.InMemorySnapshotStore;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档回放与指定版本视图的验收（api-spec.md §6、Phase 5 的"回放准确性"）。
 *
 * <p>两条被验证的性质：
 * <ol>
 *   <li><b>事件流过期后回放仍然可读</b>——数据来自 SQLite 归档，{@code truncated} 为真。
 *       没有它，一个月前的对局在回放页上是 404，而它明明是永久归档的；</li>
 *   <li><b>回放能回答"第 N 步的棋盘"</b>——{@code atVersion} 由服务端按确定性重放重建，
 *       而不是让前端按事件增量演进（那等于再实现一遍脱敏投影，AP-6）。</li>
 * </ol>
 */
class SqliteReplayArchiveTest {

    private static final String GAME_ID = "g_replay_archive";
    private static final long START_MILLIS = 1_730_000_000_000L;

    private SqliteArchiveRepository archive;
    private InfrastructureGameHarness harness;

    @BeforeEach
    void setUp() {
        archive = SqliteArchiveRepository.inMemory();
        harness = new InfrastructureGameHarness(START_MILLIS);
        harness.startNineAiGame(GAME_ID, com.wolfmind.domain.game.model.RoleComposition.STANDARD_NINE,
                com.wolfmind.domain.game.model.GameRules.DEFAULT, 20240501L);
    }

    @AfterEach
    void tearDown() {
        if (archive != null) {
            archive.close();
        }
    }

    /** 把整局事件灌进 SQLite 归档，形成"事件流已过期"的场景。 */
    private void archiveEverything() {
        List<EventEnvelope> events = harness.eventStream(GAME_ID);
        archive.archive(events.stream()
                .map(envelope -> SqliteArchiveRepository.ArchivedEvent.of(envelope, START_MILLIS))
                .toList());
        archive.upsertGame(new SqliteArchiveRepository.GameRecord(
                GAME_ID, "room_e2e", "RUNNING", "NIGHT_WOLF", 1,
                events.get(events.size() - 1).version(), 9, "{}", null, null,
                START_MILLIS, null, events.size()), START_MILLIS);
    }

    /** 一个事件流"已经过期"的视图查询：事件存储为空，只有归档可读。 */
    private GameViewQueryPort expiredStreamQuery() {
        InMemoryEventStore empty = new InMemoryEventStore();
        InMemoryGameRepository repository = new InMemoryGameRepository(
                empty, new InMemorySnapshotStore(), new InMemoryGameStateCache());
        return new InMemoryGameViewQuery(empty, repository, new SqliteReplayArchive(archive));
    }

    @Test
    @DisplayName("事件流过期后回放改读归档，并如实标记 truncated=true")
    void replayFallsBackToTheArchive() {
        archiveEverything();
        GameViewQueryPort query = expiredStreamQuery();

        GameViewQueryPort.ReplayPage page = query.replayPage(
                GAME_ID, ViewerContext.anonymous(), 1, 200, 500);

        assertTrue(page.fromArchive(), "数据来自归档时 truncated 必须为 true，否则客户端会以为流是完整的");
        assertEquals(harness.eventStream(GAME_ID).size(), page.availableVersion());
        assertFalse(page.events().isEmpty());
        // 匿名读取者只能看到 PUBLIC：归档不改变可见性，过滤仍走同一份 VisibilityFilter
        assertTrue(page.events().stream().allMatch(event ->
                event.visibility() == com.wolfmind.domain.game.model.Visibility.PUBLIC));
    }

    @Test
    @DisplayName("归档里的座位表能把账号映射回座位：参与者仍看得到自己的私有事件")
    void archivedSeatTableRestoresTheViewerContext() {
        archiveEverything();
        GameViewQueryPort query = expiredStreamQuery();

        // 九个座位都是 AI（controllerType=AI），因此任何账号都会被判定为观战者——
        // 这正是"AI 座位的 refId 永远不等于账号 id"这条不变量在归档路径上的体现。
        ViewerContext spectator = query.viewerContextFor(GAME_ID, "u_anyone");
        assertTrue(spectator.spectator());

        // 手工把一条座位记录改写成 HUMAN，验证反查确实按 refId 匹配账号
        SqliteArchiveRepository humanArchive = SqliteArchiveRepository.inMemory();
        try {
            humanArchive.archive(List.of(SqliteArchiveRepository.ArchivedEvent.of(
                    humanSeatStartedEnvelope(), START_MILLIS)));
            humanArchive.upsertGame(new SqliteArchiveRepository.GameRecord(
                    "g_human", "room_h", "FINISHED", "GAME_OVER", 2, 1, 9, "{}", "WOLF", "X",
                    START_MILLIS, START_MILLIS, 1), START_MILLIS);
            InMemoryEventStore empty = new InMemoryEventStore();
            GameViewQueryPort queryAtArchive = new InMemoryGameViewQuery(
                    empty,
                    new InMemoryGameRepository(empty, new InMemorySnapshotStore(), new InMemoryGameStateCache()),
                    new SqliteReplayArchive(humanArchive));

            assertTrue(queryAtArchive.viewerContextFor("g_human", "u_alice").participantPlayerIds()
                            .stream().anyMatch(id -> "p_3".equals(id.value())),
                    "归档里的 GAME_STARTED.seats 是权威座位表：参与者必须仍被认成参与者");
        } finally {
            humanArchive.close();
        }
    }

    @Test
    @DisplayName("事件流与归档都没有时如实报不存在，而不是返回一份空历史")
    void unknownGameIsNotSilentlyEmpty() {
        GameViewQueryPort query = expiredStreamQuery();
        assertThrows(RuntimeException.class,
                () -> query.replayPage(GAME_ID, ViewerContext.anonymous(), 1, 10, 500));
    }

    @Test
    @DisplayName("atVersion 返回该版本的重建视图，且与事件流在该版本的状态一致")
    void viewAtRebuildsTheBoardAtAGivenVersion() {
        GameViewQueryPort query = harness.viewQuery();

        GameViewSnapshot atStart = query.viewAt(GAME_ID, ViewerContext.anonymous(), 11);
        assertEquals(11, atStart.version());
        assertEquals(com.wolfmind.domain.game.model.GamePhase.NIGHT_WOLF, atStart.phase());
        assertEquals(1, atStart.round());

        // 请求超过流尾时等价于"当前视图"，这是回放页播放到末尾的正常路径，不该报错
        GameViewSnapshot beyond = query.viewAt(GAME_ID, ViewerContext.anonymous(), 10_000);
        assertEquals(11, beyond.version());

        assertThrows(com.wolfmind.domain.game.model.GameException.class,
                () -> query.viewAt(GAME_ID, ViewerContext.anonymous(), 0));
    }

    @Test
    @DisplayName("归档读取只认 games 行：归档未完成的对局不渲染成一份残缺的回放")
    void partiallyArchivedGameIsNotServed() {
        List<EventEnvelope> events = harness.eventStream(GAME_ID);
        archive.archive(events.stream()
                .map(envelope -> SqliteArchiveRepository.ArchivedEvent.of(envelope, START_MILLIS))
                .toList());
        // 刻意不写 games 行：归档器只在"事件全部写入且核对通过"之后才写它
        GameViewQueryPort query = expiredStreamQuery();
        assertThrows(RuntimeException.class,
                () -> query.replayPage(GAME_ID, ViewerContext.anonymous(), 1, 10, 500));

        ReplayArchivePort port = new SqliteReplayArchive(archive);
        assertTrue(port.game(GAME_ID).isEmpty(), "没有 games 行就等于没有可回放的归档");
    }

    /** 一条"3 号座是真人 u_alice"的最小开局事件，用于验证归档路径的座位反查。 */
    private static EventEnvelope humanSeatStartedEnvelope() {
        List<java.util.Map<String, Object>> seats = new java.util.ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            seats.add(java.util.Map.of(
                    "seatNo", seat,
                    "playerId", "p_" + seat,
                    "controllerType", seat == 3 ? "HUMAN" : "AI",
                    "refId", seat == 3 ? "u_alice" : ("p_" + seat)));
        }
        return new EventEnvelope(
                "ev_human_1", "g_human", 1, "GAME_STARTED", 1, START_MILLIS,
                "NIGHT_WOLF", 1, "SYSTEM",
                com.wolfmind.domain.game.model.Visibility.PUBLIC, "cor", "cor",
                java.util.Map.of("gameId", "g_human", "roomId", "room_h", "seats", seats, "round", 1));
    }
}
