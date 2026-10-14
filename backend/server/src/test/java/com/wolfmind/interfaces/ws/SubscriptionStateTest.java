package com.wolfmind.interfaces.ws;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.Visibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 协议语义（websocket-protocol.md §3、§8、§9）。
 *
 * <p>这个测试之所以能用**普通单元测试**写完，而不需要建一条真实连接，是因为
 * 协议语义被刻意做成了不依赖 WebSocket API 的 {@link SubscriptionState}。
 * 如果把状态机写进端点里，下面每一条断言都要先起一个服务端、握手、发帧——
 * 而它们真正要验证的是"seq 规则对不对"，与传输无关。
 *
 * <p>覆盖 §13 一致性校验清单里可离线验证的部分：阶段与超时、seq 推进与缺号、
 * 补发与 truncated、重复订阅只回一次、PING/PONG、ACK 水位、以及"发言增量不推进 seq"。
 */
class SubscriptionStateTest {

    @Test
    void filteredEventsAdvanceOnlyTheInternalCursor() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 0, false, List.of(event(1)), 1, NOW + 2);
        state.onInvisibleEvent(GAME, 2);
        assertEquals(2L, state.lastSeqOf(GAME));
        var frames = state.onEvent(event(3), NOW + 3);
        assertEquals(1, frames.size());
        assertEquals("GAME_EVENT", typeOf(frames.getFirst()));
        assertEquals(3L, frames.getFirst().seq());
        state.onInvisibleEvent(GAME, 5);
        assertEquals(3L, state.lastSeqOf(GAME), "未确认的版本 4 不能被越过");
    }

    @Test
    void snapshotResetsAnInvalidCursorAndAllowsFurtherEvents() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 99, true, List.of(), 5, NOW + 2);
        var snapshot = state.snapshotSynced(GAME, 5, Map.of("version", 5), NOW + 3);
        assertEquals(5L, snapshot.seq());
        assertEquals(5L, state.lastSeqOf(GAME));
        assertEquals("GAME_EVENT", typeOf(state.onEvent(event(6), NOW + 4).getFirst()));
    }

    @Test
    @DisplayName("ROOM_SUBSCRIBE 先登记再读快照：读快照时已能收到推送")
    void roomSubscriptionRegistersBeforeReadingSnapshot() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        List<Boolean> subscribedWhileReading = new ArrayList<>();

        var frames = state.onRoomSubscribe("r_1", roomId -> {
            subscribedWhileReading.add(state.subscribedToRoom(roomId));
            return Optional.of(new SubscriptionState.RoomSnapshot(3, Map.of("roomId", roomId)));
        }, NOW + 2);

        assertEquals(List.of(true), subscribedWhileReading);
        assertEquals("ROOM_SNAPSHOT", typeOf(frames.getFirst()));
        assertEquals(3, frames.getFirst().payload().get("version"));
        assertEquals(1, state.onRoomChanged("r_1", new SubscriptionState.RoomSnapshot(4, Map.of()), NOW + 3).size());
        assertTrue(state.onRoomChanged("r_2", new SubscriptionState.RoomSnapshot(1, Map.of()), NOW + 3).isEmpty());
    }

    @Test
    @DisplayName("房间订阅：未认证拒绝、房间不存在不留登记、超过上限拒绝、退订后不再推送")
    void roomSubscriptionRejections() {
        SubscriptionState state = newConnection();
        SubscriptionState.RoomSnapshots found = roomId -> Optional.of(new SubscriptionState.RoomSnapshot(1, Map.of()));

        assertEquals("AUTH_REQUIRED", state.onRoomSubscribe("r_1", found, NOW + 1).getFirst().payload().get("code"));
        assertFalse(state.subscribedToRoom("r_1"));

        state.onAuth("t", alwaysValid(), NOW + 2);
        var missing = state.onRoomSubscribe("r_missing", roomId -> Optional.empty(), NOW + 3);
        assertEquals("ROOM_NOT_FOUND", missing.getFirst().payload().get("code"));
        assertFalse(state.subscribedToRoom("r_missing"));

        for (int i = 1; i <= SubscriptionState.MAX_ROOM_SUBSCRIPTIONS; i++) {
            assertEquals("ROOM_SNAPSHOT", typeOf(state.onRoomSubscribe("r_" + i, found, NOW + 4).getFirst()));
        }
        var overflow = state.onRoomSubscribe("r_extra", found, NOW + 5);
        assertEquals("VALIDATION_ERROR", overflow.getFirst().payload().get("code"));
        assertEquals("ROOM_SNAPSHOT", typeOf(state.onRoomSubscribe("r_1", found, NOW + 5).getFirst()),
                "重复订阅已登记的房间不占名额，仍回快照");

        assertTrue(state.onRoomUnsubscribe("r_1", NOW + 6).isEmpty());
        assertTrue(state.onRoomChanged("r_1", new SubscriptionState.RoomSnapshot(2, Map.of()), NOW + 7).isEmpty());
    }

    private static final String GAME = "g_ws_0001";
    private static final long NOW = 1_730_000_000_000L;

    private static SubscriptionState newConnection() {
        SubscriptionState state = new SubscriptionState("conn_1");
        state.onConnected(NOW);
        return state;
    }

    /** 总是校验成功的会话查询替身。 */
    private static SubscriptionState.SessionLookup alwaysValid() {
        return token -> Optional.of(new SubscriptionState.SessionLookup.Verified("u_1", "wolf01", "s_1"));
    }

    /** 总是失败的会话查询替身。 */
    private static SubscriptionState.SessionLookup alwaysInvalid() {
        return token -> Optional.empty();
    }

    private static EventEnvelope event(int version) {
        return new EventEnvelope(
                "e_" + version, GAME, version, EventType.PHASE_CHANGED.code(), 1,
                NOW + version, GamePhase.NIGHT_WOLF.name(), 1, "SYSTEM", Visibility.PUBLIC,
                "cmd_" + version, "cor_" + version, Map.of("version", version));
    }

    private static List<EventEnvelope> events(int from, int to) {
        List<EventEnvelope> list = new ArrayList<>();
        for (int version = from; version <= to; version++) {
            list.add(event(version));
        }
        return list;
    }

    private static String typeOf(ServerFrame frame) {
        return frame.type();
    }

    // ------------------------------------------------------------------ 阶段与认证

    @Test
    @DisplayName("新连接处于 CONNECTED 阶段，未认证")
    void startsUnconnected() {
        SubscriptionState state = newConnection();

        assertEquals(SubscriptionState.Phase.CONNECTED, state.phase());
        assertFalse(state.authenticated());
        assertNull(state.userId());
        assertTrue(state.subscribedGames().isEmpty());
    }

    @Test
    @DisplayName("AUTH 成功：进入 AUTHENTICATED 并下发协商参数（心跳/空闲/积压上限）")
    void authSucceeds() {
        SubscriptionState state = newConnection();

        List<ServerFrame> frames = state.onAuth("good-token", alwaysValid(), NOW + 1);

        assertEquals(1, frames.size());
        ServerFrame authOk = frames.get(0);
        assertEquals(ServerFrame.Type.AUTH_OK.name(), typeOf(authOk));
        assertEquals("u_1", authOk.payload().get("userId"));
        // 三个协商参数必须由服务端下发：客户端不能自己假设心跳与积压上限。
        assertEquals(SubscriptionState.HEARTBEAT_INTERVAL_MILLIS, authOk.payload().get("heartbeatIntervalMs"));
        assertEquals(SubscriptionState.IDLE_TIMEOUT_MILLIS, authOk.payload().get("idleTimeoutMs"));
        assertEquals(SubscriptionState.MAX_PENDING_FRAMES, authOk.payload().get("maxPendingFrames"));
        assertEquals(SubscriptionState.Phase.AUTHENTICATED, state.phase());
        assertTrue(state.authenticated());
    }

    @Test
    @DisplayName("AUTH 失败：返回 ERROR 并关闭连接（4401，客户端不重连）")
    void authFailureCloses() {
        SubscriptionState state = newConnection();

        List<ServerFrame> frames = state.onAuth("bad-token", alwaysInvalid(), NOW + 1);

        assertEquals(1, frames.size());
        assertEquals(ServerFrame.Type.ERROR.name(), typeOf(frames.get(0)));
        assertEquals("AUTH_REQUIRED", frames.get(0).payload().get("code"));
        assertEquals(SubscriptionState.Phase.CLOSED, state.phase());
        assertFalse(state.authenticated());
    }

    @Test
    @DisplayName("重复 AUTH 返回 ERROR 但不关闭连接（重复认证不应断开已有订阅）")
    void repeatedAuthDoesNotClose() {
        SubscriptionState state = newConnection();
        state.onAuth("good-token", alwaysValid(), NOW + 1);

        List<ServerFrame> frames = state.onAuth("good-token", alwaysValid(), NOW + 2);

        assertEquals("AUTH_ALREADY_DONE", frames.get(0).payload().get("code"));
        assertEquals(SubscriptionState.Phase.AUTHENTICATED, state.phase(), "连接必须保持可用");
    }

    @Test
    @DisplayName("未认证就订阅：返回 AUTH_REQUIRED，不建立订阅")
    void subscribeBeforeAuthIsRejected() {
        SubscriptionState state = newConnection();

        List<ServerFrame> frames = state.onSubscribe(GAME, 0, false, List.of(), 10, NOW + 1);

        assertEquals("AUTH_REQUIRED", frames.get(0).payload().get("code"));
        assertFalse(state.subscribedTo(GAME));
    }

    @Test
    @DisplayName("认证超时：3 秒内没发 AUTH 即判定超时（§3.2）")
    void authTimesOut() {
        SubscriptionState state = newConnection();

        assertFalse(state.authTimedOut(NOW + SubscriptionState.AUTH_TIMEOUT_MILLIS - 1));
        assertTrue(state.authTimedOut(NOW + SubscriptionState.AUTH_TIMEOUT_MILLIS));

        // 已认证后不再判认证超时。
        state.onAuth("t", alwaysValid(), NOW + 1);
        assertFalse(state.authTimedOut(NOW + 60_000L));
    }

    @Test
    @DisplayName("空闲超时：45 秒内没有任何客户端帧则关闭（PING 与 ACK 算有效活动）")
    void idleTimesOut() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);

        assertFalse(state.idleTimedOut(NOW + SubscriptionState.IDLE_TIMEOUT_MILLIS - 1));
        // 最后活动时刻是 NOW+1（onAuth），因此恰好超时的时刻是 NOW+1+45s。
        long expiredAt = NOW + 1 + SubscriptionState.IDLE_TIMEOUT_MILLIS;
        assertTrue(state.idleTimedOut(expiredAt));

        // PING 刷新空闲计时：只订阅不发言的连接不该因空闲被断开。
        // 注意必须在超时**之前**发 PING——超时后客户端已经收不到响应了。
        state.onPing(expiredAt - 1);
        // 距 PING 时刻（expiredAt-1）不满 45 秒，因此不应判超时。
        assertFalse(state.idleTimedOut(expiredAt + SubscriptionState.IDLE_TIMEOUT_MILLIS - 2),
                "PING 之后的 45 秒内不应判超时");
        // 距 PING 恰好 45 秒：超时。
        assertTrue(state.idleTimedOut(expiredAt + SubscriptionState.IDLE_TIMEOUT_MILLIS - 1));
    }

    // ------------------------------------------------------------------ 订阅与补发

    @Test
    @DisplayName("首次订阅（lastSeq=0）不补发历史，由网关提供授权快照")
    void firstSubscribeDoesNotReplayHistory() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);

        List<ServerFrame> frames = state.onSubscribe(GAME, 0, false, events(1, 3), 3, NOW + 2);

        assertEquals(1, frames.size(), "只确认订阅，历史由当前快照替代");
        ServerFrame subscribed = frames.get(0);
        assertEquals(ServerFrame.Type.SUBSCRIBED.name(), typeOf(subscribed));
        assertEquals(GAME, subscribed.payload().get("gameId"));
        // from = lastSeq + 1（§8.1）
        // from = lastSeq + 1：首次订阅 lastSeq=0，所以第一条事件的 seq 是 1。
        assertEquals(1L, subscribed.payload().get("from"));
        assertEquals(3L, subscribed.payload().get("to"));
        assertEquals(false, subscribed.payload().get("truncated"));
        // scope 由服务端判定，请求里不带观战参数（§6.1）
        assertEquals("PLAYER", subscribed.payload().get("scope"));

        assertEquals(3L, state.lastSeqOf(GAME));
    }

    @Test
    @DisplayName("RESUME 只补发缺口：从 lastSeq+1 开始，不回放已确认部分")
    void resumeReplaysOnlyGap() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 0, false, events(1, 5), 5, NOW + 2);

        // 断线后重连，客户端声称处理到 3。
        SubscriptionState reconnected = newConnection();
        reconnected.onAuth("t", alwaysValid(), NOW + 3);
        List<ServerFrame> frames = reconnected.onSubscribe(GAME, 3, true, events(1, 5), 5, NOW + 4);

        List<Long> eventSeqs = frames.stream()
                .filter(f -> typeOf(f).equals("GAME_EVENT"))
                .map(ServerFrame::seq).toList();
        assertEquals(true, frames.get(0).payload().get("resumed"), "RESUME 应标记 resumed=true");
        // 补发 4 与 5；1..3 不应重发。
        // 只检查 GAME_EVENT 的 seq：SUBSCRIBED / SYNC_REQUIRED 的 seq 是连接内自增号，
        // 把它算进来会误判成"重发了版本 1"。
        assertEquals(List.of(4L, 5L), eventSeqs, "只应补发缺口里的两条");
    }

    @Test
    @DisplayName("truncated：请求区间超过流尾时只发 SUBSCRIBED 并标记 truncated，不假装补发完整")
    void truncatedWhenBeyondStreamTail() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);

        List<ServerFrame> frames = state.onSubscribe(GAME, 99, true, List.of(), 5, NOW + 2);

        ServerFrame subscribed = frames.stream()
                .filter(f -> typeOf(f).equals("SUBSCRIBED")).findFirst().orElseThrow();
        assertEquals(true, subscribed.payload().get("truncated"),
                "请求 99 而服务端只有 5：那些版本已不在流里");
        assertTrue(frames.stream().anyMatch(f -> typeOf(f).equals("SYNC_REQUIRED")),
                "无法补发时必须发 SYNC_REQUIRED，让客户端改走全量快照");
    }

    @Test
    @DisplayName("重复订阅只回一次 SUBSCRIBED（§6.1）")
    void duplicateSubscribeRepliesOnce() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 5, true, List.of(), 5, NOW + 2);

        List<ServerFrame> again = state.onSubscribe(GAME, 5, true, List.of(), 5, NOW + 3);

        long subscribedCount = again.stream().filter(f -> typeOf(f).equals("SUBSCRIBED")).count();
        assertEquals(1, subscribedCount, "同一 lastSeq 的重复订阅应只回一次确认");
        assertTrue(again.stream().noneMatch(f -> typeOf(f).equals("GAME_EVENT")),
                "没有缺口时不应补发任何事件");
    }

    @Test
    @DisplayName("未订阅的对局不会收到推送（订阅键只有 gameId，§6.1）")
    void eventsForOtherGamesAreNotDelivered() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 0, false, List.of(), 0, NOW + 2);

        EventEnvelope other = new EventEnvelope(
                "e_other", "g_other", 1, EventType.PHASE_CHANGED.code(), 1,
                NOW, GamePhase.NIGHT_WOLF.name(), 1, "SYSTEM", Visibility.PUBLIC,
                "cmd", "cor", Map.of());

        assertTrue(state.onEvent(other, NOW + 3).isEmpty());
    }

    // ------------------------------------------------------------------ seq 规则

    @Test
    @DisplayName("seq 连续推进；重复版本被丢弃（至少一次投递的幂等消费）")
    void seqAdvancesAndDuplicatesDropped() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 0, false, List.of(), 0, NOW + 2);

        assertEquals(1, state.onEvent(event(1), NOW + 3).size());
        assertEquals(1L, state.lastSeqOf(GAME));

        assertTrue(state.onEvent(event(1), NOW + 4).isEmpty(), "重复版本必须丢弃");
        assertEquals(1L, state.lastSeqOf(GAME), "丢弃的重复不得推进游标");

        assertEquals(1, state.onEvent(event(2), NOW + 5).size());
        assertEquals(2L, state.lastSeqOf(GAME));
    }

    @Test
    @DisplayName("缺号：缓存该事件并要求 RESUME，绝不跳过（跳过会永久丢失状态且客户端无法察觉）")
    void seqGapTriggersSyncRequired() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 1, true, List.of(), 1, NOW + 2);
        assertEquals(1L, state.lastSeqOf(GAME));

        // 跳过 2 直接给 3。
        List<ServerFrame> frames = state.onEvent(event(3), NOW + 3);

        assertEquals(1, frames.size());
        assertEquals("SYNC_REQUIRED", typeOf(frames.get(0)));
        assertEquals("SEQ_GAP", frames.get(0).payload().get("reason"));
        assertEquals(1L, frames.get(0).payload().get("lastSeq"));
        assertEquals(3L, frames.get(0).payload().get("serverSeq"));
        assertEquals(1L, state.lastSeqOf(GAME), "缺号时游标不得推进");
        assertEquals(1, state.pendingCount(), "缺号的事件必须缓存，不能丢掉");
    }

    @Test
    @DisplayName("GAME_EVENT 的 seq 等于事件 version（§4.3）")
    void gameEventSeqEqualsVersion() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        // 游标先推到 6：只有"下一个刚好是 7"时才应直接投递，否则会走缺号路径。
        state.onSubscribe(GAME, 6, true, List.of(), 6, NOW + 2);
        assertEquals(6L, state.lastSeqOf(GAME));

        ServerFrame frame = state.onEvent(event(7), NOW + 3).get(0);

        assertEquals(ServerFrame.Type.GAME_EVENT.name(), typeOf(frame));
        assertEquals(7L, frame.seq(), "seq 必须是事件版本，而不是连接内自增号");
    }

    @Test
    @DisplayName("发言增量不推进 seq（它是展示更新，不是领域事件，可优先丢弃）")
    void speechDeltaDoesNotAdvanceSeq() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 0, false, List.of(), 0, NOW + 2);
        long before = state.lastSeqOf(GAME);

        List<ServerFrame> frames = state.onSpeechDelta(
                GAME, "p_3", 3, "sp_1", "我", 0, false, NOW + 3);

        assertEquals(1, frames.size());
        assertEquals("AGENT_SPEECH_DELTA", typeOf(frames.get(0)));
        Map<String, Object> payload = frames.get(0).payload();
        // 这七个字段正好对应前端的 preview: {speechId, seatNo, content}
        assertEquals(GAME, payload.get("gameId"));
        assertEquals("sp_1", payload.get("speechId"));
        assertEquals(3, payload.get("seatNo"));
        assertEquals("我", payload.get("delta"));
        assertEquals(before, state.lastSeqOf(GAME), "展示更新不得推进状态序号");
    }

    // ------------------------------------------------------------------ 其它管理帧

    @Test
    @DisplayName("PING 回 PONG（含 serverSeq 与 serverTime）")
    void pingPong() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);

        List<ServerFrame> frames = state.onPing(NOW + 2);

        assertEquals("PONG", typeOf(frames.get(0)));
        assertNotNull(frames.get(0).payload().get("serverSeq"));
        assertNotNull(frames.get(0).payload().get("serverTime"));
    }

    @Test
    @DisplayName("ACK 只推进已确认水位，不影响 lastSeq；已确认的积压被释放")
    void ackReleasesBacklogOnly() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 1, true, List.of(), 1, NOW + 2);
        state.onEvent(event(3), NOW + 3);
        assertEquals(1, state.pendingCount());

        // 确认到 2：不应释放 version=3 的那条。
        state.onAck(2, NOW + 4);
        assertEquals(1, state.pendingCount(), "确认水位未覆盖的积压不得释放");
        assertEquals(1L, state.lastSeqOf(GAME), "ACK 不得推进状态序号");

        state.onAck(3, NOW + 5);
        assertEquals(0, state.pendingCount(), "已确认的积压应被释放");
    }

    @Test
    @DisplayName("UNSUBSCRIBE 返回 to 并移除订阅；全部退订后回到 AUTHENTICATED")
    void unsubscribe() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);
        state.onSubscribe(GAME, 3, true, List.of(), 3, NOW + 2);

        List<ServerFrame> frames = state.onUnsubscribe(GAME, NOW + 3);

        assertEquals("UNSUBSCRIBED", typeOf(frames.get(0)));
        assertEquals(GAME, frames.get(0).payload().get("gameId"));
        assertEquals(3L, frames.get(0).payload().get("to"));
        assertFalse(state.subscribedTo(GAME));
        assertEquals(SubscriptionState.Phase.AUTHENTICATED, state.phase(),
                "全部退订后回到已认证未订阅");
    }

    @Test
    @DisplayName("ServerFrame 信封：六个字段齐全，payload 固定为空对象而不是 null")
    void serverFrameEnvelopeIsComplete() {
        SubscriptionState state = newConnection();
        state.onAuth("t", alwaysValid(), NOW + 1);

        ServerFrame ping = state.onPing(NOW + 2).get(0);
        Map<String, Object> wire = ping.toWire();

        assertEquals(1, wire.get("v"), "协议版本当前为 1");
        assertNotNull(wire.get("id"));
        assertNotNull(wire.get("type"));
        assertNotNull(wire.get("ts"));
        assertNotNull(wire.get("seq"));
        assertNotNull(wire.get("payload"));
        // 六个字段，一个不多一个不少（§13：没有目录外的帧类型与信封字段）。
        assertEquals(6, wire.size(), "信封字段数必须与 §4.1 一致，实际=" + wire.keySet());
    }

    @Test
    @DisplayName("客户端帧只有 id/type/payload 三个字段（没有 v/ts/seq）")
    void clientFrameHasThreeFields() {
        ClientFrame auth = new ClientFrame.Auth("f_1", "token");
        assertEquals("AUTH", auth.type());
        assertEquals(Map.of("token", "token"), auth.payload());

        ClientFrame subscribe = new ClientFrame.Subscribe("f_2", GAME, 7);
        assertEquals("SUBSCRIBE", subscribe.type());
        assertEquals(7L, subscribe.payload().get("lastSeq"));

        ClientFrame ping = new ClientFrame.Ping("f_3");
        assertEquals(Map.of(), ping.payload(), "无参数的帧使用空对象");
    }

    @Test
    @DisplayName("关闭码表：4401/4403/4408/4429/1011 与协议 §3.6 一致")
    void closeCodesMatchContract() {
        assertEquals(4401, ServerFrame.CloseCode.UNAUTHENTICATED.code());
        assertEquals(4403, ServerFrame.CloseCode.FORBIDDEN.code());
        assertEquals(4408, ServerFrame.CloseCode.TIMEOUT.code());
        assertEquals(4429, ServerFrame.CloseCode.RATE_LIMITED.code());
        assertEquals(1011, ServerFrame.CloseCode.INTERNAL.code());
    }

    @Test
    @DisplayName("服务端帧类型是封闭集合（§5.2 的 10 种，含 ROOM_SNAPSHOT）")
    void serverFrameTypesAreClosed() {
        assertEquals(10, ServerFrame.Type.values().length,
                "帧目录外的类型不允许出现（§13），实际=" + List.of(ServerFrame.Type.values()));
    }
}
