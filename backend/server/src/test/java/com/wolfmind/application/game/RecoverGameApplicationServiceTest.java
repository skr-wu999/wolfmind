package com.wolfmind.application.game;

import com.wolfmind.application.fake.FakeEventStore;
import com.wolfmind.application.fake.FakeGameRepository;
import com.wolfmind.application.fake.FakeLock;
import com.wolfmind.application.fake.FakePublisher;
import com.wolfmind.application.fake.FakeSnapshotStore;
import com.wolfmind.application.fake.FakeTime;
import com.wolfmind.application.fake.GameFixtures;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RecoverGameApplicationService}：event-design.md §11 的恢复流程与一致性校验。 */
class RecoverGameApplicationServiceTest {

    private static final String GAME_ID = "g_recover_1";
    private static final int STARTED_VERSION = 11;

    private FakeEventStore store;
    private FakeSnapshotStore snapshots;
    private FakeGameRepository repository;
    private FakeLock lock;
    private FakePublisher publisher;
    private FakeTime time;
    private RecoverGameApplicationService service;
    private Game game;

    @BeforeEach
    void setUp() {
        store = new FakeEventStore();
        snapshots = new FakeSnapshotStore();
        repository = new FakeGameRepository(store);
        lock = new FakeLock();
        publisher = new FakePublisher();
        time = new FakeTime(1_700_000_000_000L);
        service = new RecoverGameApplicationService(lock, store, snapshots, repository, publisher, time);
        game = GameFixtures.registeredStartedGame(store, repository, GAME_ID);
    }

    @Test
    @DisplayName("无快照恢复：全量重放后追加 GAME_RECOVERED（JUDGE），并强制落快照")
    void recoversWithoutSnapshot() {
        ActionResult result = service.recover(GAME_ID, RecoverGameApplicationService.SOURCE_CRASH_RECOVERY, "cor-r1");

        assertTrue(result.accepted());
        assertEquals(12, result.version());
        assertEquals(List.of("GAME_RECOVERED"), result.eventTypes());

        EventEnvelope recovered = store.at(GAME_ID, 12);
        assertEquals("GAME_RECOVERED", recovered.type());
        assertEquals(Visibility.JUDGE, recovered.visibility());
        assertEquals("SYSTEM", recovered.actorId());
        assertEquals("cor-r1", recovered.correlationId());
        assertEquals(time.nowMillis(), recovered.occurredAt());
        assertEquals(0, recovered.payload().get("snapshotVersion"));
        assertEquals(STARTED_VERSION, recovered.payload().get("replayedTo"));
        assertEquals("CRASH_RECOVERY", recovered.payload().get("source"));

        assertEquals(STARTED_VERSION, repository.lastCommit().expectedVersion());
        assertEquals(GameRepositoryPort.SnapshotPolicy.FORCE, repository.lastCommit().snapshotPolicy());
        assertEquals(1, publisher.realtime().size());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(GAME_ID));
    }

    @Test
    @DisplayName("有快照恢复：只校验快照之后的增量事件，恢复事件记录快照版本")
    void recoversFromSnapshotTail() {
        snapshots.put(GAME_ID, 5);

        ActionResult result = service.recover(GAME_ID, RecoverGameApplicationService.SOURCE_ADMIN, "cor-r2");

        assertEquals(12, result.version());
        assertEquals(5, store.at(GAME_ID, 12).payload().get("snapshotVersion"));
        assertEquals(STARTED_VERSION, store.at(GAME_ID, 12).payload().get("replayedTo"));
        assertEquals("ADMIN", store.at(GAME_ID, 12).payload().get("source"));
    }

    @Test
    @DisplayName("事件流有版本空洞：拒绝恢复（REPLAY_OUT_OF_ORDER），不写任何事件")
    void rejectsGappedStream() {
        // 版本 12 缺失、直接出现 13：事件流被裁剪或并发写坏的典型形态。
        EventEnvelope orphan = EventEnvelope.of(game.pendingEvents().get(0), GAME_ID, 13);
        store.putUnchecked(GAME_ID, orphan);

        GameException error = assertThrows(
                GameException.class,
                () -> service.recover(GAME_ID, RecoverGameApplicationService.SOURCE_ADMIN, "cor-r3"));

        assertEquals(DomainErrorCode.REPLAY_OUT_OF_ORDER, error.code());
        assertEquals(0, repository.commitCount());
        assertEquals(1, lock.unlockCalls());
    }

    @Test
    @DisplayName("快照版本超过事件流尾：拒绝恢复，避免用不可信的缓存覆盖真相")
    void rejectsSnapshotAheadOfStream() {
        snapshots.put(GAME_ID, 30);

        GameException error = assertThrows(
                GameException.class,
                () -> service.recover(GAME_ID, RecoverGameApplicationService.SOURCE_ADMIN, "cor-r4"));

        assertEquals(DomainErrorCode.REPLAY_OUT_OF_ORDER, error.code());
        assertEquals(0, repository.commitCount());
    }

    @Test
    @DisplayName("聚合恢复版本与事件流尾不一致：拒绝恢复")
    void rejectsVersionMismatch() {
        repository.forceVersion(GAME_ID, 5);

        GameException error = assertThrows(
                GameException.class,
                () -> service.recover(GAME_ID, RecoverGameApplicationService.SOURCE_ADMIN, "cor-r5"));

        assertEquals(DomainErrorCode.REPLAY_OUT_OF_ORDER, error.code());
        assertEquals(0, repository.commitCount());
    }

    @Test
    @DisplayName("事件流不存在：GAME_NOT_RUNNING，不做隐式新建")
    void missingStreamCannotBeRecovered() {
        GameException error = assertThrows(
                GameException.class,
                () -> service.recover("g_missing", RecoverGameApplicationService.SOURCE_ADMIN, "cor-r6"));

        assertEquals(DomainErrorCode.GAME_NOT_RUNNING, error.code());
        assertEquals(1, lock.unlockCalls());
    }

    @Test
    @DisplayName("锁被占用：GAME_BUSY（恢复期间的写请求必须等待）")
    void busyLockFailsFast() {
        lock.forceBusy(true);

        GameException error = assertThrows(
                GameException.class,
                () -> service.recover(GAME_ID, RecoverGameApplicationService.SOURCE_ADMIN, "cor-r7"));

        assertEquals(DomainErrorCode.GAME_BUSY, error.code());
        assertEquals(0, repository.loadCalls());
        assertEquals(0, lock.unlockCalls());
    }

    @Test
    @DisplayName("恢复来源为空：VALIDATION_ERROR")
    void blankSourceRejected() {
        GameException error = assertThrows(
                GameException.class, () -> service.recover(GAME_ID, "  ", "cor-r8"));

        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
        assertEquals(0, lock.lockCalls());
    }
}
