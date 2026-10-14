package com.wolfmind.interfaces.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重同步的两条路径：`SYNC_REQUIRED` 与 `GAME_SNAPSHOT_SYNC`（websocket-protocol.md §8）。
 *
 * <p>这两条路径**此前都没有测试**——`snapshotSynced` 有实现、网关有调用点，
 * 但没有任何断言说明"什么时候该发哪一个"。
 *
 * <p>它们的区别是真实存在的，而且决定客户端能不能恢复：
 *
 * <ul>
 *   <li>{@code SYNC_REQUIRED}：补发区间**被 500 条上限截断**。
 *       客户端仍有可用状态，只是缺一段——因此它应当**重新订阅并带 `lastSeq=0`
 *       走全量回放**（`realGameAdapter` 的 `onSyncRequired` 就是这么做的）。</li>
 *   <li>{@code GAME_SNAPSHOT_SYNC}：客户端的 `lastSeq` **超过了服务端持有的流尾**
 *       （典型场景：服务端重启且历史未恢复）。此时增量补发毫无意义，
 *       服务端直接给一份**当前视图**让客户端原地重建。</li>
 * </ul>
 *
 * <p>把两者混为一条会得到两种错误行为：对截断的客户端只发快照，它会丢掉
 * 缺的那段历史；对水位超前的客户端只发 `SYNC_REQUIRED`，
 * 它会在"重新订阅 → 仍然超前"之间**无限循环**。
 */
class ResyncPathTest {

    private static final String GAME = "g_resync";
    private static final long NOW = 1_730_000_000_000L;

    private static SubscriptionState authenticated() {
        SubscriptionState state = new SubscriptionState("conn_resync", 1_000L, 20);
        state.onConnected(NOW);
        state.onAuth("tok", token -> java.util.Optional.of(
                new SubscriptionState.SessionLookup.Verified("u_1", "甲", "s_1")), NOW);
        return state;
    }

    private static long count(SubscriptionState state, List<ServerFrame> frames, String type) {
        return frames.stream().filter(frame -> type.equals(frame.type())).count();
    }

    @Test
    @DisplayName("补发被截断时发 SYNC_REQUIRED，不发快照")
    void truncatedReplayAsksForResync() {
        SubscriptionState state = authenticated();

        // lastSeq=99 而服务端只到 5，且这是 **RESUME**：
        // 缺口超出流尾 → 截断。
        List<ServerFrame> frames = state.onSubscribe(GAME, 99, true, List.of(), 5, NOW + 10);

        assertEquals(1, count(state, frames, "SYNC_REQUIRED"),
                "截断必须让客户端知道要重同步；实际帧：" + frames.stream().map(ServerFrame::type).toList());
        assertEquals(0, count(state, frames, "GAME_SNAPSHOT_SYNC"),
                "截断场景不该直接发快照——那会让客户端丢掉缺口那段历史");
        // 仍然要回 SUBSCRIBED：否则客户端会以为自己没有被受理。
        assertEquals(1, count(state, frames, "SUBSCRIBED"),
                "截断之后仍须回 SUBSCRIBED，实际帧："
                        + frames.stream().map(ServerFrame::type).toList());
        ServerFrame subscribed = frames.stream()
                .filter(frame -> "SUBSCRIBED".equals(frame.type())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, subscribed.payload().get("truncated"),
                "SUBSCRIBED 必须把 truncated 报给客户端");
    }

    @Test
    @DisplayName("缺口在流尾之内时不发 SYNC_REQUIRED（正常补发）")
    void replayWithinTailIsNotTruncated() {
        SubscriptionState state = authenticated();

        List<ServerFrame> frames = state.onSubscribe(GAME, 2, true, List.of(), 5, NOW + 10);

        assertEquals(0, count(state, frames, "SYNC_REQUIRED"),
                "2 → 5 的缺口在流尾之内，不该要求重同步；实际帧："
                        + frames.stream().map(ServerFrame::type).toList());
        ServerFrame subscribed = frames.stream()
                .filter(frame -> "SUBSCRIBED".equals(frame.type())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, subscribed.payload().get("truncated"),
                "未截断时 truncated 应为 false");
    }

    @Test
    @DisplayName("快照帧的 seq 等于同步点版本，并把游标推到那里")
    void snapshotSyncAdvancesCursorToSyncPoint() {
        SubscriptionState state = authenticated();
        state.onSubscribe(GAME, 0, false, List.of(), 5, NOW + 1);

        ServerFrame snapshot = state.snapshotSynced(GAME, 5, Map.of("version", 5), NOW + 2);

        assertEquals("GAME_SNAPSHOT_SYNC", snapshot.type());
        // §4.3：快照的 seq 是同步点版本，不是连接内自增号。
        assertEquals(5L, snapshot.seq(),
                "快照的 seq 必须等于 replayedTo，否则客户端会把水位算错");
        assertEquals(5L, state.lastSeqOf(GAME),
                "快照之后游标必须停在同步点，后续实时帧才不会判成缺号");
    }

    @Test
    @DisplayName("快照之后的实时帧按同步点顺序到达，不被判成缺号")
    void eventAfterSnapshotUsesAdvancedCursor() {
        SubscriptionState state = authenticated();
        state.onSubscribe(GAME, 0, false, List.of(), 3, NOW + 1);
        state.snapshotSynced(GAME, 3, Map.of(), NOW + 2);

        List<ServerFrame> frames = state.onEvent(event(4), NOW + 3);

        assertEquals(1, count(state, frames, "GAME_EVENT"),
                "同步点之后的下一个版本必须被接受；实际帧："
                        + frames.stream().map(ServerFrame::type).toList());
        assertEquals(0, count(state, frames, "SYNC_REQUIRED"),
                "紧随同步点的事件不该被判成缺号");
    }

    @Test
    @DisplayName("未认证的连接拿不到快照（快照含完整视图，必须先鉴权）")
    void snapshotRequiresAuthentication() {
        SubscriptionState state = new SubscriptionState("conn_anon", 1_000L, 20);
        state.onConnected(NOW);

        // 未认证时订阅会被拒为 AUTH_REQUIRED，因此不会有快照路径。
        List<ServerFrame> frames = state.onSubscribe(GAME, 0, false, List.of(), 5, NOW + 1);

        assertEquals(1, count(state, frames, "ERROR"),
                "未认证订阅应被拒；实际帧：" + frames.stream().map(ServerFrame::type).toList());
        assertEquals(0, count(state, frames, "GAME_SNAPSHOT_SYNC"),
                "未认证连接不得收到快照——它含完整视图，包括该身份不该看到的部分");
        ServerFrame error = frames.stream()
                .filter(frame -> "ERROR".equals(frame.type())).findFirst().orElseThrow();
        assertEquals("AUTH_REQUIRED", error.payload().get("code"));
    }

    @Test
    @DisplayName("快照的 payload 带可辨识的元数据（客户端能区分重建原因）")
    void snapshotPayloadIsIdentifiable() {
        SubscriptionState state = authenticated();
        state.onSubscribe(GAME, 0, false, List.of(), 2, NOW + 1);

        ServerFrame snapshot = state.snapshotSynced(GAME, 2, Map.of("source", "FULL_REPLAY"), NOW + 2);

        assertNotNull(snapshot.payload());
        assertFalse(snapshot.payload().isEmpty(), "快照 payload 不该为空");
    }

    /** 构造一个已提交事件信封。 */
    private static com.wolfmind.domain.game.event.EventEnvelope event(int version) {
        return new com.wolfmind.domain.game.event.EventEnvelope(
                "e_" + version, GAME, version, "PHASE_CHANGED", 1, NOW,
                "DAY_DISCUSS", 1, "SYSTEM",
                com.wolfmind.domain.game.model.Visibility.PUBLIC,
                "cmd", "cor", Map.of("to", "DAY_VOTE"));
    }
}
