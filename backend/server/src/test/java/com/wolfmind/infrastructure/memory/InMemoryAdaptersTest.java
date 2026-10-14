package com.wolfmind.infrastructure.memory;

import com.wolfmind.infrastructure.InfrastructureTestEvents;

import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkillState;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照存储、锁与幂等键的语义测试。
 *
 * <p>这三者的共同点是"都带时间语义"，因此用可控时钟而不是 {@code Thread.sleep}：
 * 等待真实时间会让测试变慢且不稳定。
 */
class InMemoryAdaptersTest {

    // ---------------- 快照存储 ----------------

    @Test
    @DisplayName("快照按版本保存、幂等重复写不重复计数，并只保留最近 3 个版本")
    void snapshotStoreRetainsThreeVersions() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        for (int version = 1; version <= 5; version++) {
            store.save(snapshot("g_1", version));
        }

        assertEquals(List.of(3, 4, 5), store.retainedVersions("g_1"), "只保留最近 3 个版本");
        assertEquals(5, store.latest("g_1").orElseThrow().version());
        assertTrue(store.atVersion("g_1", 2).isEmpty(), "被淘汰的历史版本不再可寻址");
        assertEquals(5, store.acceptedWriteCount("g_1"), "5 个不同版本各落盘一次");
    }

    @Test
    @DisplayName("同一版本重复保存相同内容时幂等：不覆盖、不重复计数")
    void snapshotStoreIsIdempotentPerVersion() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        SnapshotStorePort.Snapshot snapshot = snapshot("g_2", 7);

        store.save(snapshot);
        store.save(snapshot);
        store.save(snapshot("g_2", 7));

        assertEquals(1, store.acceptedWriteCount("g_2"), "相同内容的重复保存只应被接受一次");
        assertEquals(snapshot, store.latest("g_2").orElseThrow());
    }

    @Test
    @DisplayName("同一版本被写入不同内容时拒绝：快照不可覆盖历史")
    void snapshotStoreRejectsConflictingContentForSameVersion() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        store.save(snapshot("g_3", 4));

        SnapshotStorePort.Snapshot conflicting = new SnapshotStorePort.Snapshot(
                "g_3", 4, "{\"other\":true}", "abc");
        assertThrows(IllegalStateException.class, () -> store.save(conflicting));
        assertNotEquals("abc", store.latest("g_3").orElseThrow().checksum());
    }

    @Test
    @DisplayName("快照与增量事件叠加后的状态由 GameSnapshotCodec 往返保持一致")
    void snapshotCodecRoundTripsAggregateState() {
        GameSnapshot state = gameSnapshot("g_4", 12);
        String encoded = GameSnapshotCodec.encode(state);

        GameSnapshot decoded = GameSnapshotCodec.decode(encoded);

        assertEquals(state, decoded, "快照状态文本往返后必须逐字段相同");
        assertEquals(GameSnapshotCodec.checksum(encoded), GameSnapshotCodec.checksum(GameSnapshotCodec.encode(decoded)),
                "同一状态的 checksum 必须稳定");
        assertNotEquals(GameSnapshotCodec.checksum(encoded), GameSnapshotCodec.checksum(encoded + " "),
                "checksum 必须对文本变化敏感");
    }

    @Test
    @DisplayName("快照文本损坏时解析失败，而不是给出半成品状态")
    void snapshotCodecRejectsCorruptedText() {
        String encoded = GameSnapshotCodec.encode(gameSnapshot("g_5", 3));
        String corrupted = encoded.replace("\"version\":3", "\"version\":\"three\"");

        assertThrows(GameSnapshotCodec.SnapshotFormatException.class, () -> GameSnapshotCodec.decode(corrupted));
        assertThrows(com.wolfmind.infrastructure.json.JsonException.class, () -> GameSnapshotCodec.decode("{"));
        assertThrows(GameSnapshotCodec.SnapshotFormatException.class,
                () -> GameSnapshotCodec.decode("{\"gameId\":\"g\",\"version\":1}"));
    }

    @Test
    @DisplayName("快照中的 null 字段（无回合、无胜者）往返后仍为 null")
    void snapshotCodecPreservesNulls() {
        GameSnapshot state = gameSnapshot("g_6", 1);
        GameSnapshot decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(state));

        org.junit.jupiter.api.Assertions.assertNull(decoded.currentTurn());
        org.junit.jupiter.api.Assertions.assertNull(decoded.winner());
        org.junit.jupiter.api.Assertions.assertNull(decoded.finishReason());
        assertTrue(decoded.votes().isEmpty());
    }

    @Test
    @DisplayName("夜聊计数与 stateVersion 往返保持；旧快照缺这两个键时按无夜聊解码")
    void snapshotCodecRoundTripsWolfChatState() {
        GameSnapshot base = gameSnapshot("g_7", 14);
        GameSnapshot withChat = new GameSnapshot(
                base.gameId(), base.version(), base.status(), base.phase(), base.round(),
                base.roleComposition(), base.rules(), base.roomId(), base.players(), base.turnOrder(),
                base.turnIndex(), base.previousDiscussionFirst(), base.currentTurn(), base.wolfKillTarget(),
                base.wolfNominations(), base.witchSaved(), base.witchPoisoned(), base.witchPassed(),
                base.inspectedTarget(), base.inspectResult(), base.votes(), base.deaths(), base.resumePhase(),
                base.pendingAfterLastWords(), base.pendingLastWords(), base.lastWordsIndex(), base.pendingHunter(),
                base.pendingShotVictim(), base.winner(), base.finishReason(),
                java.util.Map.of(1, 2), 12);

        String encoded = GameSnapshotCodec.encode(withChat);
        GameSnapshot decoded = GameSnapshotCodec.decode(encoded);
        assertEquals(withChat, decoded);
        assertEquals(2, decoded.wolfChatCount(1));
        assertEquals(12, decoded.stateVersion());

        String legacy = encoded
                .replace(",\"wolfChatCounts\":[{\"wolfSeatNo\":1,\"count\":2}]", "")
                .replace(",\"stateVersion\":12", "");
        assertTrue(!legacy.contains("wolfChatCounts") && !legacy.contains("stateVersion"), legacy);
        GameSnapshot old = GameSnapshotCodec.decode(legacy);
        assertTrue(old.wolfChatCounts().isEmpty());
        assertEquals(14, old.stateVersion(), "旧快照不放宽版本校验");
    }

    // ---------------- 锁 ----------------

    @Test
    @DisplayName("同一对局同时只有一个持有者，第二个 tryLock 返回空")
    void lockIsExclusiveNonBlocking() {
        MutableClock clock = new MutableClock();
        InMemoryLock lock = new InMemoryLock(clock, 3000);

        Optional<GameLockPort.LockToken> first = lock.tryLock("g_lock");
        Optional<GameLockPort.LockToken> second = lock.tryLock("g_lock");

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty(), "第二个写者必须拿到 GAME_BUSY 的输入，而不是阻塞");
        assertTrue(lock.isLocked("g_lock"));
        assertEquals("g_lock", first.orElseThrow().gameId());
        assertEquals(3000, first.orElseThrow().expiresAt() - first.orElseThrow().acquiredAt());
    }

    @Test
    @DisplayName("TTL 到期后新写者可以接管（模拟持有者崩溃）")
    void lockExpiresAfterTtl() {
        MutableClock clock = new MutableClock();
        InMemoryLock lock = new InMemoryLock(clock, 3000);

        GameLockPort.LockToken first = lock.tryLock("g_ttl").orElseThrow();
        clock.advance(Duration.ofMillis(2999));
        assertTrue(lock.tryLock("g_ttl").isEmpty(), "未到期不得接管");

        clock.advance(Duration.ofMillis(1));
        Optional<GameLockPort.LockToken> second = lock.tryLock("g_ttl");
        assertTrue(second.isPresent(), "到期后必须能接管");
        assertNotEquals(first.ownerId(), second.orElseThrow().ownerId(), "接管者是新的持有者");
    }

    @Test
    @DisplayName("释放非本次持有的锁是安全操作：不会误删接管者的锁")
    void unlockOtherHolderIsSafe() {
        MutableClock clock = new MutableClock();
        InMemoryLock lock = new InMemoryLock(clock, 3000);

        GameLockPort.LockToken original = lock.tryLock("g_safe").orElseThrow();
        clock.advance(Duration.ofMillis(3001));
        GameLockPort.LockToken taker = lock.tryLock("g_safe").orElseThrow();

        // 原持有者在 TTL 到期后才来释放：必须不删接管者的锁
        lock.unlock(original);
        assertTrue(lock.isLocked("g_safe"), "过期的原持有者不得删掉接管者的锁");
        assertTrue(lock.tryLock("g_safe").isEmpty());

        // 接管者释放后锁才真正空出
        lock.unlock(taker);
        assertFalse(lock.isLocked("g_safe"));
        assertTrue(lock.tryLock("g_safe").isPresent());

        // 重复释放、释放未知令牌都是安全的 no-op
        lock.unlock(taker);
        lock.unlock(new GameLockPort.LockToken("g_other", "nobody", 0, 0));
        lock.unlock(null);
        assertTrue(lock.isLocked("g_safe"));
    }

    // ---------------- 幂等 ----------------

    @Test
    @DisplayName("幂等键命中返回首次结果，重复记录相同结果不报错")
    void idempotencyReturnsFirstResult() {
        MutableClock clock = new MutableClock();
        InMemoryIdempotencyPort port = new InMemoryIdempotencyPort(clock);

        IdempotencyPort.RecordedResult first = new IdempotencyPort.RecordedResult(
                "g_idem", "key-1", 12, List.of("e-12"), List.of("PLAYER_VOTED"), "digest");
        port.record("g_idem", "key-1", first);
        port.record("g_idem", "key-1", first);

        assertEquals(Optional.of(first), port.find("g_idem", "key-1"));
        assertEquals(1, port.size(), "相同键只保留一条记录");
    }

    @Test
    @DisplayName("同一幂等键写入不同结果被拒绝：重复提交必须返回首次结果")
    void idempotencyRejectsDifferentResultForSameKey() {
        InMemoryIdempotencyPort port = new InMemoryIdempotencyPort(new MutableClock());
        port.record("g_idem2", "key-1",
                new IdempotencyPort.RecordedResult("g_idem2", "key-1", 3, List.of("e-3"), List.of("X"), "d1"));

        IdempotencyPort.RecordedResult different =
                new IdempotencyPort.RecordedResult("g_idem2", "key-1", 4, List.of("e-4"), List.of("Y"), "d2");
        assertThrows(IllegalStateException.class, () -> port.record("g_idem2", "key-1", different));
    }

    @Test
    @DisplayName("幂等键按 TTL 过期，过期后可以重新记录")
    void idempotencyExpiresAfterTtl() {
        MutableClock clock = new MutableClock();
        InMemoryIdempotencyPort port = new InMemoryIdempotencyPort(clock);

        port.record("g_idem3", "key-1",
                new IdempotencyPort.RecordedResult("g_idem3", "key-1", 1, List.of("e-1"), List.of("X"), "d1"));
        assertTrue(port.find("g_idem3", "key-1").isPresent());

        clock.advance(Duration.ofMinutes(10));
        assertTrue(port.find("g_idem3", "key-1").isEmpty(), "TTL 到期后必须视为未命中");
        assertEquals(0, port.size());

        port.record("g_idem3", "key-1",
                new IdempotencyPort.RecordedResult("g_idem3", "key-1", 2, List.of("e-2"), List.of("Y"), "d2"));
        assertEquals(2, port.find("g_idem3", "key-1").orElseThrow().version());
    }

    @Test
    @DisplayName("不同对局或不同幂等键互不命中")
    void idempotencyKeysAreScoped() {
        InMemoryIdempotencyPort port = new InMemoryIdempotencyPort(new MutableClock());
        port.record("g_a", "k", new IdempotencyPort.RecordedResult("g_a", "k", 1, List.of("e"), List.of("X"), "d"));

        assertTrue(port.find("g_b", "k").isEmpty(), "幂等键按 gameId 隔离");
        assertTrue(port.find("g_a", "k2").isEmpty());
        assertTrue(port.find("g_a:b", "k").isEmpty(), "拼接键不得产生歧义命中");
        assertThrows(IllegalArgumentException.class, () -> port.find("g_a", " "));
    }

    // ---------------- 时钟与发布 ----------------

    @Test
    @DisplayName("SystemTimePort 与可控时钟共享同一时间基准")
    void systemTimePortUsesInjectedClock() {
        MutableClock clock = new MutableClock();
        SystemTimePort time = new SystemTimePort(clock);

        assertEquals(clock.millis(), time.nowMillis());
        clock.advance(Duration.ofSeconds(5));
        assertEquals(clock.millis(), time.nowMillis());
    }

    @Test
    @DisplayName("发布器按消费组隔离记录，实时推送单独记录")
    void publisherRecordsPerConsumerGroup() {
        InMemoryEventPublisher publisher = new InMemoryEventPublisher();
        var events = InfrastructureTestEvents.startEventStream("g_pub");

        publisher.publish("cg-projection", events);
        publisher.publish("cg-projection", events);
        publisher.publishRealtime(events);

        // 两次发布同一批：至少一次投递允许重复，发布器不做去重（去重是消费层的责任）
        assertEquals(22, publisher.publishedTo("cg-projection").size());
        // 实时推送走 publishRealtime，不会混进任何消费组
        assertEquals(0, publisher.publishedTo("cg-broadcast").size());
        assertEquals(11, publisher.realtimePublished().size());
        assertEquals(List.of("cg-projection"), publisher.consumerGroups());

        // 空列表是安全的 no-op：把"没有事件"当成错误会让调用方在每个分支上写一次判空。
        publisher.publish("cg-insight", List.of());
        publisher.publishRealtime(List.of());
        assertTrue(publisher.publishedTo("cg-insight").isEmpty());
    }

    // ---------------- 夹具 ----------------

    private static SnapshotStorePort.Snapshot snapshot(String gameId, int version) {
        String state = "{\"gameId\":\"" + gameId + "\",\"version\":" + version + "}";
        return new SnapshotStorePort.Snapshot(gameId, version, state, GameSnapshotCodec.checksum(state));
    }

    private static GameSnapshot gameSnapshot(String gameId, int version) {
        List<PlayerSnapshot> players = List.of(new PlayerSnapshot(
                PlayerId.forSeat(SeatNo.of(1)), SeatNo.of(1), Role.WEREWOLF, Camp.WOLF, true,
                PlayerController.human("u_1"), true, SkillState.NONE));
        return new GameSnapshot(
                GameId.of(gameId), version, GameStatus.RUNNING, GamePhase.NIGHT_WOLF, 1,
                RoleComposition.STANDARD_NINE, GameRules.DEFAULT, "room_1",
                players,
                List.of(1),
                0,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                false,
                null,
                null,
                java.util.Map.of(),
                List.of(),
                null,
                null,
                List.of(),
                0,
                null,
                null,
                null,
                null);
    }

    /** 可控时钟：{@code millis()} 从固定基点开始，测试显式推进。 */
    private static final class MutableClock extends Clock {

        private static final Instant BASE = Instant.parse("2025-01-01T00:00:00Z");

        private Instant now = BASE;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
