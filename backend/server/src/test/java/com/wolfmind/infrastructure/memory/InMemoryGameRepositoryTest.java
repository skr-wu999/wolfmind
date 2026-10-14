package com.wolfmind.infrastructure.memory;

import com.wolfmind.infrastructure.InfrastructureTestEvents;

import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.SeatNo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照 + 增量恢复的等价性（event-design.md §11、INV-15、INV-16）。
 *
 * <p>这是 Phase 2 的核心验收点：恢复得到的状态必须与"从头全量重放"逐字段相同，
 * 否则快照就把自己变成了第二个真相来源。
 */
class InMemoryGameRepositoryTest {

    private static final String GAME_ID = "g_repo_0001";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
    private final InMemoryGameStateCache stateCache = new InMemoryGameStateCache();
    private final InMemoryGameRepository repository =
            new InMemoryGameRepository(eventStore, snapshotStore, stateCache, 50);

    /** 用真实开局事件流把对局推到版本 11。 */
    private void seedStartedGame() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        eventStore.append(GAME_ID, 0, events);
    }

    @Test
    @DisplayName("commit 追加事件并返回提交后的版本与事件标识")
    void commitAppendsAndReportsVersion() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);

        var result = repository.commit(GAME_ID, 0, events,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);

        assertEquals(11, result.version());
        assertEquals(11, result.eventIds().size());
        assertEquals(11, result.eventTypes().size());
        assertEquals(events.get(0).eventId(), result.eventIds().get(0));
        assertEquals(11, eventStore.currentVersion(GAME_ID));
    }

    @Test
    @DisplayName("快照缺失时从版本 1 全量重放，且能被领域聚合真实还原")
    void loadWithoutSnapshotReplaysFromVersionOne() {
        seedStartedGame();
        repository.resetLoadCounters();

        var loaded = repository.load(GAME_ID);

        assertEquals(11, loaded.version());
        assertEquals(1, repository.fullReplayLoadCount(), "恰好走一次全量重放");
        assertEquals(0, repository.incrementalLoadCount());
        assertEquals(9, loaded.game().snapshot().players().size(), "聚合真的吃掉了全部 11 条事件");

        // 与"直接对事件流做一次全量重放"逐字段一致
        Game direct = Game.create(GameId.of(GAME_ID));
        for (EventEnvelope envelope : eventStore.read(GAME_ID, 1, Integer.MAX_VALUE)) {
            direct.replay(com.wolfmind.domain.game.event.EventCodec.decode(envelope));
        }
        assertEquals(direct.snapshot(), loaded.game().snapshot());
    }

    @Test
    @DisplayName("INV-15：快照 + 增量恢复与全量重放逐字段相同")
    void snapshotPlusIncrementalEqualsFullReplay() {
        seedStartedGame();

        // 基线状态 + 版本 11 的快照
        GameSnapshot baseline = repository.load(GAME_ID).game().snapshot();
        assertEquals(11, repository.snapshotNow(GAME_ID));

        // 追加一条真实事件（狼人提名），版本 12
        List<EventEnvelope> more = nominateOneWolf(11);
        assertFalse(more.isEmpty(), "夹具应产生至少一条事件");
        repository.commit(GAME_ID, 11, more,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.NONE);
        int head = eventStore.currentVersion(GAME_ID);

        // 恢复路径：快照(11) + 增量(12..head)
        var recovered = repository.load(GAME_ID);
        assertEquals(head, recovered.version());
        assertTrue(repository.incrementalLoadCount() >= 1, "必须走快照 + 增量路径");

        // 重建路径：从版本 1 全量重放
        Game full = Game.create(GameId.of(GAME_ID));
        for (EventEnvelope envelope : eventStore.read(GAME_ID, 1, Integer.MAX_VALUE)) {
            full.replay(com.wolfmind.domain.game.event.EventCodec.decode(envelope));
        }

        assertEquals(full.snapshot(), recovered.game().snapshot(),
                "快照 + 增量恢复的状态必须与全量重放逐字段相同");
        assertNotEquals(baseline, recovered.game().snapshot(), "增量事件确实改变了状态，测试才有意义");
    }

    @Test
    @DisplayName("INTERVAL 策略只在版本为间隔整数倍时落快照")
    void intervalPolicySavesOnMultiples() {
        var repository50 = new InMemoryGameRepository(eventStore, snapshotStore, stateCache, 11);
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);

        repository50.commit(GAME_ID, 0, events,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);

        Optional<SnapshotStorePort.Snapshot> snapshot = snapshotStore.latest(GAME_ID);
        assertTrue(snapshot.isPresent(), "版本 11 是间隔 11 的整数倍，应生成快照");
        assertEquals(11, snapshot.orElseThrow().version());
        assertEquals(GameSnapshotCodec.checksum(snapshot.orElseThrow().state()),
                snapshot.orElseThrow().checksum(), "落盘时必须同时写入正确的 checksum");
    }

    @Test
    @DisplayName("NONE 策略不落快照；FORCE 策略无条件落快照")
    void noneAndForcePolicies() {
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);

        repository.commit(GAME_ID, 0, events,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.NONE);
        assertTrue(snapshotStore.latest(GAME_ID).isEmpty(), "NONE 不得落快照");

        repository.commit(GAME_ID, 11, List.of(),
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.FORCE);
        assertTrue(snapshotStore.latest(GAME_ID).isPresent(), "FORCE 必须落快照");
    }

    @Test
    @DisplayName("快照 checksum 被篡改时退化为全量重放，而不是相信坏快照")
    void fallsBackWhenChecksumMismatches() {
        seedStartedGame();
        repository.snapshotNow(GAME_ID);
        SnapshotStorePort.Snapshot good = snapshotStore.latest(GAME_ID).orElseThrow();

        // 伪造一个 checksum 不匹配的快照（模拟存储损坏）
        SnapshotStorePort.Snapshot corrupted = new SnapshotStorePort.Snapshot(
                GAME_ID, good.version(), good.state(), "deadbeef");
        // 用独立存储避免"同版本不同内容"的保护，模拟真实场景：读到别人写坏的文件
        var corruptedStore = new InMemorySnapshotStore();
        corruptedStore.save(corrupted);

        var repositoryWithCorruptedSnapshot =
                new InMemoryGameRepository(eventStore, corruptedStore, stateCache, 50);
        var loaded = repositoryWithCorruptedSnapshot.load(GAME_ID);

        assertEquals(1, repositoryWithCorruptedSnapshot.checksumDegradationCount(),
                "校验失败必须被计数，运维要看到它");
        assertEquals(11, loaded.version(), "仍然必须能加载出正确版本");
        assertEquals(repository.load(GAME_ID).game().snapshot(), loaded.game().snapshot(),
                "退化到全量重放后状态仍与全量重放一致");
    }

    @Test
    @DisplayName("对局不存在时抛 GAME_NOT_RUNNING，而不是返回空聚合")
    void loadUnknownGameFails() {
        var error = assertThrows(com.wolfmind.domain.game.model.GameException.class,
                () -> repository.load("g_missing"));
        assertEquals(com.wolfmind.domain.game.model.DomainErrorCode.GAME_NOT_RUNNING, error.code());
    }

    @Test
    @DisplayName("commit 的版本冲突不留下半批事件")
    void commitConflictLeavesNoPartialWrite() {
        seedStartedGame();
        List<EventEnvelope> stale = nominateOneWolf(11);

        assertThrows(com.wolfmind.domain.game.model.GameException.class,
                () -> repository.commit(GAME_ID, 5, stale,
                        com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL));

        assertEquals(11, eventStore.currentVersion(GAME_ID), "冲突提交不得改变事件流");
        assertTrue(snapshotStore.latest(GAME_ID).isEmpty(), "冲突提交不得落快照");
    }

    @Test
    @DisplayName("snapshotNow 落盘后 load 走快照路径，且状态不变")
    void snapshotNowRoundTripsState() {
        seedStartedGame();
        GameSnapshot before = repository.load(GAME_ID).game().snapshot();
        int version = repository.snapshotNow(GAME_ID);

        var after = repository.load(GAME_ID);
        assertEquals(version, after.version());
        assertEquals(before, after.game().snapshot(), "快照落盘不改变状态");
    }

    @Test
    @DisplayName("refreshProjection 把权威状态写入状态投影")
    void refreshProjectionUpdatesCache() {
        seedStartedGame();
        assertTrue(stateCache.get(GAME_ID) == null, "commit 未生成快照时投影可以暂时为空");

        GameSnapshot snapshot = repository.refreshProjection(GAME_ID);
        assertEquals(snapshot, stateCache.get(GAME_ID));
    }

    @Test
    @DisplayName("statusOf 在版本未变时不重新加载聚合，版本前进后重新读取")
    void statusOfReusesStatusUntilVersionMoves() {
        seedStartedGame();
        repository.resetLoadCounters();

        assertEquals(Optional.of(com.wolfmind.domain.game.model.GameStatus.RUNNING), repository.statusOf(GAME_ID));
        assertEquals(Optional.of(com.wolfmind.domain.game.model.GameStatus.RUNNING), repository.statusOf(GAME_ID));
        assertEquals(1, repository.fullReplayLoadCount() + repository.incrementalLoadCount(),
                "同一版本只应加载一次");

        repository.commit(GAME_ID, 11, nominateOneWolf(11),
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.NONE);
        assertEquals(Optional.of(com.wolfmind.domain.game.model.GameStatus.RUNNING), repository.statusOf(GAME_ID));
        assertEquals(2, repository.fullReplayLoadCount() + repository.incrementalLoadCount(),
                "版本前进后必须重新加载");

        assertEquals(Optional.empty(), repository.statusOf("g_missing"));
    }

    @Test
    @DisplayName("statusAt 一次返回版本与状态；只有观察到终态后 knownTerminal 才为真")
    void statusAtReportsVersionAndTerminalKnowledge() {
        seedStartedGame();

        assertFalse(repository.knownTerminal(GAME_ID), "尚未查询过的对局不能当成终态");
        var status = repository.statusAt(GAME_ID).orElseThrow();
        assertEquals(11, status.version());
        assertEquals(com.wolfmind.domain.game.model.GameStatus.RUNNING, status.status());
        assertFalse(repository.knownTerminal(GAME_ID), "运行中的对局不是终态");
    }

    /**
     * 用一次真实的狼人提名产生增量事件。
     *
     * <p>刻意不用 {@code Game.advance()}：{@code NIGHT_WOLF} 在没有提名时是稳定状态，
     * 推进返回空列表——用它当夹具会让"增量恢复"测试在空数据集上通过，等于没测。
     */
    private List<EventEnvelope> nominateOneWolf(int expectedVersion) {
        Game game = Game.create(GameId.of(GAME_ID));
        for (EventEnvelope envelope : eventStore.read(GAME_ID, 1, Integer.MAX_VALUE)) {
            game.replay(com.wolfmind.domain.game.event.EventCodec.decode(envelope));
        }
        SeatNo wolfSeat = game.wolvesAwaitingNomination().get(0);
        List<DomainEvent> produced = game.handle(
                com.wolfmind.domain.game.model.WolfKillAction.at(wolfSeat.value(), 1),
                ActionContext.system("cmd_nominate", InfrastructureTestEvents.BASE_TIME + 900));
        List<EventEnvelope> envelopes = new java.util.ArrayList<>();
        int version = expectedVersion;
        for (DomainEvent event : produced) {
            version++;
            envelopes.add(EventEnvelope.of(event, GAME_ID, version));
        }
        return envelopes;
    }
}
