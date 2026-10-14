package com.wolfmind.application.game;

import com.wolfmind.application.fake.FakeEventStore;
import com.wolfmind.application.fake.FakeGameRepository;
import com.wolfmind.application.fake.FakeLock;
import com.wolfmind.application.fake.FakePublisher;
import com.wolfmind.application.fake.FakeTime;
import com.wolfmind.application.fake.GameFixtures;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.StartGameCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StartGameApplicationService}：开局从版本 0 起写入 11 条连续事件。 */
class StartGameApplicationServiceTest {

    private static final String GAME_ID = "g_start_1";

    private FakeEventStore store;
    private FakeGameRepository repository;
    private FakeLock lock;
    private FakePublisher publisher;
    private FakeTime time;
    private StartGameApplicationService service;

    @BeforeEach
    void setUp() {
        store = new FakeEventStore();
        repository = new FakeGameRepository(store);
        lock = new FakeLock();
        publisher = new FakePublisher();
        time = new FakeTime(1_700_000_000_000L);
        service = new StartGameApplicationService(lock, store, repository, publisher, time);
    }

    @Test
    @DisplayName("开局：GAME_STARTED + 9×ROLE_ASSIGNED + PHASE_CHANGED，版本 1..11 连续")
    void startsGameWithElevenEvents() {
        ActionResult result = service.start(
                GameFixtures.standardCommand(GAME_ID, "room-1"), "u_owner", "cor-start");

        assertTrue(result.accepted());
        assertEquals(11, result.version());
        assertEquals(11, result.eventIds().size());
        assertEquals(11, result.eventTypes().size());
        assertEquals("GAME_STARTED", result.eventTypes().get(0));
        assertEquals("PHASE_CHANGED", result.eventTypes().get(10));
        assertEquals(9, result.eventTypes().stream().filter("ROLE_ASSIGNED"::equals).count());

        assertEquals(0, repository.lastCommit().expectedVersion());
        assertEquals(11, store.currentVersion(GAME_ID));
        for (int version = 1; version <= 11; version++) {
            assertEquals(version, store.at(GAME_ID, version).version());
        }
        EventEnvelope started = store.at(GAME_ID, 1);
        assertEquals("GAME_STARTED", started.type());
        assertEquals("u_owner", started.actorId());
        assertEquals(GamePhase.GAME_INIT.name(), started.phase());

        assertEquals(Set.of("cg-projection", "cg-agent", "cg-insight"), publisher.groups());
        assertEquals(11, publisher.realtime().size());
        assertFalse(lock.isHeld(GAME_ID));
        assertEquals(1, lock.unlockCalls());
    }

    @Test
    @DisplayName("重复开局：GAME_ALREADY_STARTED，不覆盖已提交的事件流")
    void rejectsSecondStart() {
        service.start(GameFixtures.standardCommand(GAME_ID, "room-1"), "u_owner", "cor-start");

        GameException error = assertThrows(
                GameException.class,
                () -> service.start(GameFixtures.standardCommand(GAME_ID, "room-1"), "u_owner", "cor-start-2"));

        assertEquals(DomainErrorCode.GAME_ALREADY_STARTED, error.code());
        assertEquals(1, repository.commitCount());
        assertEquals(11, store.currentVersion(GAME_ID));
        // 两次调用各取一次锁：第二次也要在锁内判断"是否已开局"，因此都释放。
        assertEquals(2, lock.unlockCalls());
        assertFalse(lock.isHeld(GAME_ID));
    }

    @Test
    @DisplayName("单写者锁被占用：GAME_BUSY，未写入任何事件")
    void busyLockFailsFast() {
        lock.forceBusy(true);

        GameException error = assertThrows(
                GameException.class,
                () -> service.start(GameFixtures.standardCommand(GAME_ID, "room-1"), "u_owner", null));

        assertEquals(DomainErrorCode.GAME_BUSY, error.code());
        assertEquals(0, repository.commitCount());
        assertFalse(store.exists(GAME_ID));
    }

    @Test
    @DisplayName("存在未就绪座位：由领域拒绝（INV-R2），应用层不自己判断")
    void unreadySeatIsRejectedByDomain() {
        List<SeatAssignment> seats = new ArrayList<>();
        seats.add(SeatAssignment.of(1, PlayerController.human("u_1"), false));
        for (int seatNo = 2; seatNo <= 9; seatNo++) {
            seats.add(SeatAssignment.human(seatNo, "u_" + seatNo));
        }
        StartGameCommand command = new StartGameCommand(
                GameId.of(GAME_ID), "room-1", seats, RoleComposition.STANDARD_NINE, GameRules.DEFAULT);

        GameException error = assertThrows(
                GameException.class, () -> service.start(command, "u_owner", "cor-start"));

        // NOT_READY 而不是 VALIDATION_ERROR：请求格式没有问题，是**对局当前状态**还不允许开局。
        // 400 会让客户端以为要改请求体，而它该做的是等其他人准备。
        // 这条断言原先写的是 VALIDATION_ERROR（跟着实现一起错的），端到端冒烟才发现——
        // 单测与实现同时错的时候，两边会互相印证。
        assertEquals(DomainErrorCode.NOT_READY, error.code());
        assertEquals(0, repository.commitCount());
        assertFalse(store.exists(GAME_ID));
        assertEquals(1, lock.unlockCalls());
    }
}
