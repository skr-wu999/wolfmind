package com.wolfmind.application.game;

import com.wolfmind.application.fake.FakeEventStore;
import com.wolfmind.application.fake.FakeGameRepository;
import com.wolfmind.application.fake.FakeIdempotency;
import com.wolfmind.application.fake.FakeLock;
import com.wolfmind.application.fake.FakePublisher;
import com.wolfmind.application.fake.FakeTime;
import com.wolfmind.application.fake.GameFixtures;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubmitActionApplicationService}：api-spec.md §5.3 的九步流程。
 *
 * <p>用假端口 + 真实领域聚合：应用层的编排（幂等、锁、版本、提交、发布）用假端口观察，
 * "行动是否合法"由领域决定，测试不重复实现规则。
 */
class SubmitActionApplicationServiceTest {

    private static final String GAME_ID = "g_submit_1";
    private static final int STARTED_VERSION = 11;

    private FakeEventStore store;
    private FakeGameRepository repository;
    private FakeLock lock;
    private FakeIdempotency idempotency;
    private FakePublisher publisher;
    private FakeTime time;
    private SubmitActionApplicationService service;

    private Game game;
    private PlayerSnapshot wolf;
    private PlayerSnapshot villager;

    @BeforeEach
    void setUp() {
        store = new FakeEventStore();
        repository = new FakeGameRepository(store);
        lock = new FakeLock();
        idempotency = new FakeIdempotency();
        publisher = new FakePublisher();
        time = new FakeTime(1_700_000_000_000L);
        service = new SubmitActionApplicationService(
                new ActionCodec(), idempotency, lock, repository, publisher, time);

        game = GameFixtures.registeredStartedGame(store, repository, GAME_ID);
        wolf = GameFixtures.playerWithRole(game, Role.WEREWOLF);
        villager = GameFixtures.playerWithRole(game, Role.VILLAGER);
        assertEquals(STARTED_VERSION, repository.game(GAME_ID).version());
    }

    @Test
    @DisplayName("狼人提名：提交成功、事件版本从 12 起连续、先持久化后发布")
    void submitsWolfKill() {
        ActionResult result = service.submit(wolfKill(wolf.controller().refId(), villager.seatNo().value(), "idem-1"));

        assertTrue(result.accepted());
        assertFalse(result.replayed());
        assertEquals(12, result.version());
        assertEquals(List.of("WOLF_KILL_NOMINATED"), result.eventTypes());
        assertEquals(result.eventIds(), repository.lastCommit().events().stream().map(EventEnvelope::eventId).toList());
        assertEquals(STARTED_VERSION, repository.lastCommit().expectedVersion());

        EventEnvelope committed = store.at(GAME_ID, 12);
        assertEquals("WOLF_KILL_NOMINATED", committed.type());
        assertEquals(wolf.controller().refId(), committed.actorId());
        assertEquals("cor-1", committed.correlationId());
        assertEquals(time.nowMillis(), committed.occurredAt());
        assertEquals(Visibility.WOLF, committed.visibility());

        assertEquals(Set.of("cg-projection", "cg-agent", "cg-insight"), publisher.groups());
        assertEquals(1, publisher.publishedTo("cg-projection").size());
        assertEquals(1, publisher.publishedTo("cg-agent").size());
        assertEquals(1, publisher.publishedTo("cg-insight").size());
        assertEquals(List.of(committed), publisher.realtime());

        assertFalse(lock.isHeld(GAME_ID));
        assertEquals(1, lock.unlockCalls());
    }

    @Test
    @DisplayName("幂等命中：返回首次结果且不加载聚合、不加锁、不写事件")
    void idempotentHitReturnsFirstResultWithoutTouchingTheGame() {
        idempotency.preset(
                GAME_ID,
                "idem-repeat",
                new com.wolfmind.application.port.IdempotencyPort.RecordedResult(
                        GAME_ID, "idem-repeat", 12, List.of("g:12-12"), List.of("PLAYER_VOTED"), "digest"));

        ActionResult result =
                service.submit(wolfKill(wolf.controller().refId(), villager.seatNo().value(), "idem-repeat"));

        assertTrue(result.accepted());
        assertTrue(result.replayed());
        assertEquals(12, result.version());
        assertEquals(List.of("PLAYER_VOTED"), result.eventTypes());
        assertEquals(0, repository.loadCalls());
        assertEquals(0, lock.lockCalls());
        assertEquals(0, repository.commitCount());
        assertTrue(publisher.groups().isEmpty());
    }

    @Test
    @DisplayName("首次提交后记录幂等结果，结果摘要非空")
    void recordsIdempotentResult() {
        service.submit(wolfKill(wolf.controller().refId(), villager.seatNo().value(), "idem-2"));

        assertTrue(idempotency.has(GAME_ID, "idem-2"));
        var recorded = idempotency.get(GAME_ID, "idem-2");
        assertEquals(12, recorded.version());
        assertEquals(List.of("WOLF_KILL_NOMINATED"), recorded.eventTypes());
        assertNotNull(recorded.resultDigest());
        assertFalse(recorded.resultDigest().isBlank());
    }

    @Test
    @DisplayName("单写者锁被占用：GAME_BUSY，且不加载聚合")
    void busyLockFailsFast() {
        lock.forceBusy(true);

        GameException error = assertThrows(
                GameException.class,
                () -> service.submit(wolfKill(wolf.controller().refId(), villager.seatNo().value(), "idem-3")));

        assertEquals(DomainErrorCode.GAME_BUSY, error.code());
        assertEquals(0, repository.loadCalls());
        assertEquals(0, repository.commitCount());
        assertEquals(0, lock.unlockCalls());
    }

    @Test
    @DisplayName("提交者不是该座位控制者：SEAT_NOT_YOURS，且锁必须释放")
    void seatNotYours() {
        SubmitActionCommand command = new SubmitActionCommand(
                GAME_ID, "u_stranger", "WOLF_KILL", Map.of("targetSeatNo", villager.seatNo().value()), 11, "idem-4", null);

        GameException error = assertThrows(GameException.class, () -> service.submit(command));

        assertEquals(DomainErrorCode.SEAT_NOT_YOURS, error.code());
        assertEquals(0, repository.commitCount());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(GAME_ID));
        assertTrue(publisher.groups().isEmpty());
    }

    @Test
    @DisplayName("expectedVersion 过期：GAME_VERSION_CONFLICT，不改状态")
    void staleExpectedVersion() {
        SubmitActionCommand command = wolfKill(wolf.controller().refId(), villager.seatNo().value(), "idem-5");
        SubmitActionCommand stale = new SubmitActionCommand(
                command.gameId(),
                command.actorId(),
                command.actionType(),
                command.params(),
                10,
                command.idempotencyKey(),
                command.correlationId());

        GameException error = assertThrows(GameException.class, () -> service.submit(stale));

        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, error.code());
        assertEquals(0, repository.commitCount());
        assertEquals(STARTED_VERSION, repository.game(GAME_ID).version());
        assertEquals(1, lock.unlockCalls());
    }

    @Test
    @DisplayName("领域拒绝行动：异常原样抛出，不写事件、不发布、锁仍释放")
    void domainRejectionPropagates() {
        SubmitActionCommand command = new SubmitActionCommand(
                GAME_ID,
                villager.controller().refId(),
                "WOLF_KILL",
                Map.of("targetSeatNo", wolf.seatNo().value()),
                11,
                "idem-6",
                null);

        GameException error = assertThrows(GameException.class, () -> service.submit(command));

        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, error.code());
        assertEquals(0, repository.commitCount());
        assertEquals(11, store.currentVersion(GAME_ID));
        assertTrue(publisher.groups().isEmpty());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(GAME_ID));
    }

    @Test
    @DisplayName("未知 actionType 在加锁前失败：明显的坏请求不占用单写者锁")
    void unknownActionTypeFailsBeforeLock() {
        SubmitActionCommand command = new SubmitActionCommand(
                GAME_ID, wolf.controller().refId(), "MAGIC", Map.of(), 11, "idem-7", null);

        GameException error = assertThrows(GameException.class, () -> service.submit(command));

        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
        assertEquals(0, lock.lockCalls());
        assertEquals(0, repository.loadCalls());
    }

    @Test
    @DisplayName("参数形态非法（缺目标）：VALIDATION_ERROR 且锁被释放")
    void invalidParamsReleaseLock() {
        SubmitActionCommand command =
                new SubmitActionCommand(GAME_ID, wolf.controller().refId(), "VOTE", Map.of(), 11, "idem-8", null);

        GameException error = assertThrows(GameException.class, () -> service.submit(command));

        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
        assertEquals(1, lock.lockCalls());
        assertEquals(1, lock.unlockCalls());
        assertEquals(0, repository.commitCount());
    }

    @Test
    @DisplayName("发布失败不影响已提交结果：命令仍返回 accepted 与真实版本")
    void publishFailureDoesNotBreakAcceptedResult() {
        publisher.failAll(true);

        ActionResult result =
                service.submit(wolfKill(wolf.controller().refId(), villager.seatNo().value(), "idem-9"));

        assertTrue(result.accepted());
        assertEquals(12, result.version());
        assertEquals(12, store.currentVersion(GAME_ID));
        assertTrue(idempotency.has(GAME_ID, "idem-9"));
        assertEquals(1, lock.unlockCalls());
    }

    @Test
    @DisplayName("连续两次提交：版本与事件流依次推进")
    void sequentialSubmissionsAdvanceVersion() {
        List<PlayerSnapshot> wolves = game.snapshot().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .toList();
        assertTrue(wolves.size() >= 2);

        ActionResult first = service.submit(
                wolfKill(wolves.get(0).controller().refId(), villager.seatNo().value(), "idem-10"));
        ActionResult second = service.submit(
                new SubmitActionCommand(
                        GAME_ID,
                        wolves.get(1).controller().refId(),
                        "WOLF_KILL",
                        Map.of("targetSeatNo", villager.seatNo().value()),
                        12,
                        "idem-11",
                        "cor-11"));

        assertEquals(12, first.version());
        assertEquals(13, second.version());
        assertEquals(13, store.currentVersion(GAME_ID));
        assertEquals(13, repository.game(GAME_ID).version());
        assertEquals(List.of("WOLF_KILL_NOMINATED"), second.eventTypes());
        assertEquals(2, repository.commitCount());
    }

    @Test
    @DisplayName("夜聊：WOLF_CHAT_SENT 是 WOLF 可见事件，版本前进一格")
    void submitsWolfChat() {
        ActionResult result = service.submit(wolfChat(wolf.controller().refId(), "刀谁", STARTED_VERSION, "idem-chat-1"));

        assertTrue(result.accepted());
        assertEquals(12, result.version());
        assertEquals(List.of("WOLF_CHAT_SENT"), result.eventTypes());
        EventEnvelope committed = store.at(GAME_ID, 12);
        assertEquals(Visibility.WOLF, committed.visibility());
        assertEquals("刀谁", committed.payload().get("content"));
        assertEquals(wolf.seatNo().value(), ((Number) committed.payload().get("wolfSeatNo")).intValue());
    }

    @Test
    @DisplayName("expectedVersion 之后只有夜聊：提名不算过期，仍按当前版本提交")
    void chatOnlyGapIsNotStale() {
        List<PlayerSnapshot> wolves = game.snapshot().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .toList();
        service.submit(wolfChat(wolves.get(1).controller().refId(), "我刀预言家", STARTED_VERSION, "idem-chat-2"));
        service.submit(wolfChat(wolves.get(1).controller().refId(), "跟我", 12, "idem-chat-3"));

        ActionResult kill = service.submit(
                wolfKill(wolves.get(0).controller().refId(), villager.seatNo().value(), "idem-chat-4"));

        assertTrue(kill.accepted());
        assertEquals(14, kill.version());
        assertEquals(13, repository.lastCommit().expectedVersion(), "提交按当前版本追加，不留空洞");
        assertEquals("WOLF_KILL_NOMINATED", store.at(GAME_ID, 14).type());
    }

    @Test
    @DisplayName("expectedVersion 之后有非夜聊事件：仍然 GAME_VERSION_CONFLICT")
    void nonChatGapIsStale() {
        List<PlayerSnapshot> wolves = game.snapshot().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .toList();
        service.submit(wolfKill(wolves.get(0).controller().refId(), villager.seatNo().value(), "idem-chat-5"));
        service.submit(wolfChat(wolves.get(0).controller().refId(), "我刀了", 12, "idem-chat-6"));

        GameException error = assertThrows(GameException.class, () -> service.submit(
                wolfChat(wolves.get(1).controller().refId(), "收到", STARTED_VERSION, "idem-chat-7")));

        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, error.code());
        assertEquals(13, store.currentVersion(GAME_ID));
    }

    @Test
    @DisplayName("expectedVersion 大于当前版本：GAME_VERSION_CONFLICT")
    void futureVersionIsRejected() {
        GameException error = assertThrows(GameException.class, () -> service.submit(
                wolfChat(wolf.controller().refId(), "未来", STARTED_VERSION + 1, "idem-chat-8")));
        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, error.code());
    }

    private SubmitActionCommand wolfChat(String actorId, String content, int expectedVersion, String idempotencyKey) {
        return new SubmitActionCommand(
                GAME_ID, actorId, "WOLF_CHAT", Map.of("content", content), expectedVersion, idempotencyKey, "cor-chat");
    }

    private SubmitActionCommand wolfKill(String actorId, int targetSeat, String idempotencyKey) {
        return new SubmitActionCommand(
                GAME_ID,
                actorId,
                "WOLF_KILL",
                Map.of("targetSeatNo", targetSeat),
                STARTED_VERSION,
                idempotencyKey,
                "cor-1");
    }
}
