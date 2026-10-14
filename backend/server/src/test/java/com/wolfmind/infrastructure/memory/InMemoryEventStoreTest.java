package com.wolfmind.infrastructure.memory;

import com.wolfmind.infrastructure.InfrastructureTestEvents;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件存储语义：版本冲突、重复写入、append 原子性。
 *
 * <p>这些用例的存在理由：内存实现是 Redis Stream 的同形替换点，如果它比 Redis 宽松，
 * 测试会在"内存下通过、Redis 下丢事件"的地方给出错误的安全感。
 */
class InMemoryEventStoreTest {

    private final InMemoryEventStore store = new InMemoryEventStore();

    @Test
    @DisplayName("按 expectedVersion 连续追加后，currentVersion 等于条数")
    void appendsContiguously() {
        String gameId = "g_1";
        List<EventEnvelope> first = InfrastructureTestEvents.startEventStream(gameId);
        assertEquals(11, first.size(), "九人开局应产生 11 条事件");

        store.append(gameId, 0, first);

        assertEquals(11, store.currentVersion(gameId));
        assertEquals(11, store.size(gameId));
        assertTrue(store.exists(gameId));
    }

    @Test
    @DisplayName("重复写入同一版本被拒绝，且不改变流内容")
    void rejectsDuplicateVersions() {
        String gameId = "g_2";
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(gameId);
        store.append(gameId, 0, events);

        List<EventEnvelope> again = List.of(InfrastructureTestEvents.bareEnvelope(gameId, 11, EventType.PHASE_CHANGED));
        GameException conflict =
                assertThrows(GameException.class, () -> store.append(gameId, 10, again));
        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, conflict.code());

        assertEquals(11, store.currentVersion(gameId), "被拒绝的写入不得改变流");
        assertEquals(11, store.size(gameId));
    }

    @Test
    @DisplayName("expectedVersion 落后于流尾时报版本冲突")
    void rejectsStaleExpectedVersion() {
        String gameId = "g_3";
        store.append(gameId, 0, InfrastructureTestEvents.startEventStream(gameId));

        GameException conflict = assertThrows(GameException.class,
                () -> store.append(gameId, 5, List.of(InfrastructureTestEvents.bareEnvelope(gameId, 6, EventType.PHASE_CHANGED))));
        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, conflict.code());
        assertTrue(conflict.getMessage().contains("11"), "冲突信息应给出实际水位：" + conflict.getMessage());
    }

    @Test
    @DisplayName("首条事件版本必须等于 expectedVersion+1")
    void rejectsWrongFirstVersion() {
        String gameId = "g_4";
        // 空流、expectedVersion=0，但首条版本写成 2：留下版本空洞 1 的风险必须先被拦住。
        List<EventEnvelope> wrong = List.of(InfrastructureTestEvents.bareEnvelope(gameId, 2, EventType.PHASE_CHANGED));

        GameException conflict = assertThrows(GameException.class, () -> store.append(gameId, 0, wrong));
        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, conflict.code());
        assertEquals(0, store.currentVersion(gameId));
        assertFalse(store.exists(gameId), "失败后不得留下空流以外的痕迹");
    }

    @Test
    @DisplayName("批内版本不连续时整批拒绝：一条都不写")
    void rejectsNonContiguousBatchAtomically() {
        String gameId = "g_5";
        List<EventEnvelope> batch = List.of(
                InfrastructureTestEvents.bareEnvelope(gameId, 1, EventType.GAME_STARTED),
                InfrastructureTestEvents.bareEnvelope(gameId, 2, EventType.PHASE_CHANGED),
                // 跳过 3，直接写 4：批内空洞
                InfrastructureTestEvents.bareEnvelope(gameId, 4, EventType.PHASE_CHANGED));

        assertThrows(GameException.class, () -> store.append(gameId, 0, batch));

        assertEquals(0, store.currentVersion(gameId), "冲突不得留下半批");
        assertFalse(store.exists(gameId));
    }

    @Test
    @DisplayName("中途冲突时前面已校验通过的事件也不写入（全成或全不写）")
    void leavesNoPartialBatch() {
        String gameId = "g_6";
        // 先用真实流写 0..10
        store.append(gameId, 0, InfrastructureTestEvents.startEventStream(gameId));

        // 第二批：11 合法、12 合法、13 重复已存在 -> 整批失败
        List<EventEnvelope> batch = new ArrayList<>();
        batch.add(InfrastructureTestEvents.bareEnvelope(gameId, 11, EventType.PHASE_CHANGED));
        batch.add(InfrastructureTestEvents.bareEnvelope(gameId, 12, EventType.PHASE_CHANGED));
        batch.add(InfrastructureTestEvents.bareEnvelope(gameId, 12, EventType.PHASE_CHANGED));

        assertThrows(GameException.class, () -> store.append(gameId, 10, batch));

        assertEquals(11, store.currentVersion(gameId), "整批失败后水位必须停在原处");
        assertEquals(11, store.size(gameId));
    }

    @Test
    @DisplayName("空批是合法的 no-op；null 批与 gameId 不一致被拒绝")
    void rejectsEmptyAndMismatchedGameId() {
        store.append("g_7", 0, List.of());
        assertFalse(store.exists("g_7"), "空批不得创建流");
        assertEquals(0, store.currentVersion("g_7"));

        assertThrows(GameException.class, () -> store.append("g_7", 0, null));

        List<EventEnvelope> mismatched = List.of(InfrastructureTestEvents.bareEnvelope("g_other", 1, EventType.GAME_STARTED));
        GameException error = assertThrows(GameException.class, () -> store.append("g_7", 0, mismatched));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
    }

    @Test
    @DisplayName("read 按版本升序返回区间，可截取增量")
    void readsVersionRange() {
        String gameId = "g_8";
        store.append(gameId, 0, InfrastructureTestEvents.startEventStream(gameId));

        List<EventEnvelope> all = store.read(gameId, 1, Integer.MAX_VALUE);
        assertEquals(11, all.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                all.stream().map(EventEnvelope::version).toList());

        List<EventEnvelope> incremental = store.read(gameId, 6, Integer.MAX_VALUE);
        assertEquals(List.of(6, 7, 8, 9, 10, 11),
                incremental.stream().map(EventEnvelope::version).toList(), "恢复重放读的是快照之后的增量");

        assertEquals(List.of(6, 7), store.read(gameId, 6, 7).stream().map(EventEnvelope::version).toList());
        assertTrue(store.read(gameId, 12, Integer.MAX_VALUE).isEmpty());
        assertTrue(store.read(gameId, 5, 4).isEmpty(), "空区间返回空列表而不是抛错");
        assertTrue(store.read("g_unknown", 1, 5).isEmpty());
    }

    @Test
    @DisplayName("read 返回不可变列表，调用方无法篡改已写入的事件")
    void readReturnsImmutableList() {
        String gameId = "g_9";
        store.append(gameId, 0, InfrastructureTestEvents.startEventStream(gameId));

        List<EventEnvelope> all = store.read(gameId, 1, Integer.MAX_VALUE);
        assertThrows(UnsupportedOperationException.class, () -> all.add(all.get(0)));

        List<EventEnvelope> copy = new ArrayList<>(all);
        Collections.reverse(copy);
        assertEquals(11, store.currentVersion(gameId), "反转副本不影响存储");
        assertSame(all.get(0), store.read(gameId, 1, 5).get(0));
    }

    @Test
    @DisplayName("payload 中的显式 null 在写入后仍然存在")
    void preservesExplicitNullPayloadValues() {
        String gameId = "g_10";
        EventEnvelope vote = InfrastructureTestEvents.envelopeWithNullPayload(gameId, 1);
        store.append(gameId, 0, List.of(vote));

        EventEnvelope stored = store.read(gameId, 1, 1).get(0);
        assertTrue(stored.payload().containsKey("targetId"), "显式 null 的键不能被丢掉");
        assertEquals(null, stored.payload().get("targetId"));
        assertEquals(true, stored.payload().get("abstain"));
    }

    @Test
    @DisplayName("不同对局的事件流互相隔离")
    void isolatesStreamsPerGame() {
        store.append("g_a", 0, InfrastructureTestEvents.startEventStream("g_a"));
        store.append("g_b", 0, InfrastructureTestEvents.startEventStream("g_b"));

        assertEquals(11, store.currentVersion("g_a"));
        assertEquals(11, store.currentVersion("g_b"));
        assertEquals(0, store.currentVersion("g_c"));
        assertFalse(store.exists("g_c"));
        assertEquals(List.of("g_a", "g_b"), store.gameIds());
    }
}
