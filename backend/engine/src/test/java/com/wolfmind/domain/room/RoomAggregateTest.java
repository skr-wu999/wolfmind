package com.wolfmind.domain.room;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.room.model.Room;
import com.wolfmind.domain.room.model.RoomId;
import com.wolfmind.domain.room.model.RoomStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Room} 聚合：不变量 INV-R1/R2/R3、幂等语义与重放一致性。
 *
 * <p>最后一条（{@code liveStateEqualsReplayedState}）是 INV-13/INV-16 的机器校验：
 * 命令路径与重放路径必须得到**同一个状态**。房间聚合容易在这里出错——因为
 * "房主移交"与"就绪计数"都依赖累计状态，一旦写在命令方法里而不在 {@code apply} 里，
 * 重放就会与实时不一致。
 */
class RoomAggregateTest {

    private static final String OWNER = "u_owner";
    private static final String ALICE = "u_alice";
    private static final String BOB = "u_bob";

    private static ActionContext ctx(int version) {
        return new ActionContext(
                "cmd_" + version, 1_730_000_000_000L + version, OWNER,
                "cmd_" + version, "cor_room", version, 0L);
    }

    private static Room newRoom() {
        Room room = Room.create(RoomId.of("r_0001"));
        room.create("雾隐村 1 号房", OWNER, Map.of("playerCount", 9), ctx(0));
        return room;
    }

    @Test
    @DisplayName("INV-R1：固定 9 个座位，创建后房主占 1 号座")
    void createSeatsOwnerAtOne() {
        Room room = newRoom();

        assertEquals(Room.SEAT_COUNT, room.seats().size());
        assertEquals(RoomStatus.WAITING, room.status());
        assertEquals(OWNER, room.ownerId());
        assertEquals(1, room.occupiedCount());
        assertEquals(SeatNo.of(1), room.seatOf(OWNER));
        // ROOM_CREATED + PLAYER_SEATED
        assertEquals(2, room.pendingEvents().size());
        assertEquals(EventType.ROOM_CREATED, room.pendingEvents().get(0).type());
        assertEquals(EventType.PLAYER_SEATED, room.pendingEvents().get(1).type());
    }

    @Test
    @DisplayName("INV-R3：每次状态变化都产生事件，且可见性都是 PUBLIC")
    void everyChangeEmitsPublicEvent() {
        Room room = newRoom();
        room.join(ALICE, ctx(2));
        room.ready(ALICE, ctx(3));

        List<DomainEvent> events = room.pendingEvents();
        List<EventType> types = events.stream().map(DomainEvent::type).toList();
        assertEquals(List.of(EventType.ROOM_CREATED, EventType.PLAYER_SEATED,
                EventType.PLAYER_SEATED, EventType.PLAYER_READY), types);
        for (DomainEvent event : events) {
            assertEquals(com.wolfmind.domain.game.model.Visibility.PUBLIC, event.visibility(),
                    "房间事件全部 PUBLIC（event-design.md 7.1）：" + event.type());
            assertFalse(event.type().isGameEvent(), "房间事件不属于对局流：" + event.type());
        }
    }

    @Test
    @DisplayName("入座幂等：同一玩家重复 join 不产生第二个事件")
    void joinIsIdempotent() {
        Room room = newRoom();
        int before = room.pendingEvents().size();

        room.join(ALICE, ctx(2));
        int afterFirst = room.pendingEvents().size();
        room.join(ALICE, ctx(3));

        assertEquals(before + 1, afterFirst);
        assertEquals(afterFirst, room.pendingEvents().size(), "重复入座不得再产生事件");
        assertEquals(2, room.occupiedCount());
    }

    @Test
    @DisplayName("准备幂等：重复 ready 不产生第二个事件（否则就绪计数会被重复触发）")
    void readyIsIdempotent() {
        Room room = newRoom();
        room.join(ALICE, ctx(2));
        room.ready(ALICE, ctx(3));
        int afterFirst = room.pendingEvents().size();

        room.ready(ALICE, ctx(4));

        assertEquals(afterFirst, room.pendingEvents().size());
        // 夹具里房主占 1 号座但未准备，ALICE 占 2 号座且已准备 → 就绪数为 1。
        assertEquals(1, room.readyCount(), "只有 ALICE 准备了");
        assertEquals(2, room.occupiedCount());
    }

    @Test
    @DisplayName("未入座者不能准备，拒绝码是 NOT_SEATED")
    void readyRequiresSeat() {
        Room room = newRoom();

        GameException error = assertThrows(GameException.class, () -> room.ready(BOB, ctx(2)));
        assertEquals(DomainErrorCode.NOT_SEATED, error.code());
    }

    @Test
    @DisplayName("INV-R1：目标座位被占时拒绝，且拒绝不产生事件")
    void takeSeatRejectsOccupiedSeat() {
        Room room = newRoom();
        room.join(ALICE, ctx(2));
        int before = room.pendingEvents().size();

        GameException error = assertThrows(GameException.class,
                () -> room.takeSeat(BOB, SeatNo.of(2), ctx(3)));

        assertEquals(DomainErrorCode.ALREADY_SEATED, error.code());
        assertEquals(before, room.pendingEvents().size(), "被拒绝的命令不得产生事件");
    }

    @Test
    @DisplayName("满座后 join 拒绝，拒绝码是 ROOM_FULL")
    void joinRejectsWhenFull() {
        Room room = newRoom();
        for (int seat = 2; seat <= Room.SEAT_COUNT; seat++) {
            room.join("u_" + seat, ctx(seat));
        }
        assertEquals(Room.SEAT_COUNT, room.occupiedCount());

        GameException error = assertThrows(GameException.class, () -> room.join("u_extra", ctx(20)));
        assertEquals(DomainErrorCode.ROOM_FULL, error.code());
    }

    @Test
    @DisplayName("AI 座位入座即就绪，不需要额外 PLAYER_READY")
    void aiSeatIsAutoReady() {
        Room room = newRoom();
        room.seatAi(OWNER, SeatNo.of(2), "agent_07", ctx(2));

        var seat = room.seatAt(SeatNo.of(2));
        assertTrue(seat.isAi());
        assertTrue(seat.ready(), "AI 座位必须自动就绪，否则开局会被卡住");
        assertTrue(seat.satisfied());
        assertEquals("agent_07", seat.controller().refId());
        assertEquals("agent_07", seat.playerId().value(),
                "AI 座位的 playerId 与控制者 refId 必须一致，提交行动靠它反查座位");
    }

    @Test
    @DisplayName("只有房主能操作 AI 座位，非房主被拒（FORBIDDEN）")
    void onlyOwnerManagesAi() {
        Room room = newRoom();
        room.join(ALICE, ctx(2));

        GameException error = assertThrows(GameException.class,
                () -> room.seatAi(ALICE, SeatNo.of(3), "agent_09", ctx(3)));
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, error.code(),
                "领域侧 forbidden() 统一映射到 ACTION_NOT_ALLOWED；接口层再按需要表达为 403");
    }

    @Test
    @DisplayName("不能把真人座位改成 AI（ALREADY_SEATED）")
    void cannotReplaceHumanWithAi() {
        Room room = newRoom();
        room.join(ALICE, ctx(2));

        GameException error = assertThrows(GameException.class,
                () -> room.seatAi(OWNER, SeatNo.of(2), "agent_07", ctx(3)));
        assertEquals(DomainErrorCode.ALREADY_SEATED, error.code());
    }

    @Test
    @DisplayName("移出 AI：产生 CONTROLLER_CHANGED + PLAYER_LEFT，座位回到空置")
    void clearAiEmptiesSeat() {
        Room room = newRoom();
        room.seatAi(OWNER, SeatNo.of(2), "agent_07", ctx(2));
        int before = room.pendingEvents().size();

        room.clearAi(OWNER, SeatNo.of(2), ctx(3));

        List<EventType> produced = room.pendingEvents().subList(before, room.pendingEvents().size())
                .stream().map(DomainEvent::type).toList();
        assertEquals(List.of(EventType.PLAYER_CONTROLLER_CHANGED, EventType.PLAYER_LEFT), produced);
        assertFalse(room.seatAt(SeatNo.of(2)).occupied());
        assertEquals(1, room.occupiedCount());
    }

    @Test
    @DisplayName("房主离开时移交房主给第一个真人座位；无真人则置空")
    void ownerHandoverOnLeave() {
        Room room = newRoom();
        room.join(ALICE, ctx(2));
        room.join(BOB, ctx(3));

        room.leave(OWNER, ctx(4));
        assertEquals(ALICE, room.ownerId(), "房主必须移交给第一个真人座位");

        room.leave(ALICE, ctx(5));
        assertEquals(BOB, room.ownerId());

        room.leave(BOB, ctx(6));
        assertNull(room.ownerId(), "没有真人时房主置空，而不是保留一个已离开的人");
    }

    @Test
    @DisplayName("INV-R2：未全部就绪不能开局（NOT_READY），全部就绪后可以")
    void startRequiresAllReady() {
        Room room = newRoom();
        GameException error = assertThrows(GameException.class,
                () -> room.startGame(OWNER, "g_0001", ctx(2)));
        assertEquals(DomainErrorCode.NOT_READY, error.code());

        room.ready(OWNER, ctx(3));
        for (int seat = 2; seat <= Room.SEAT_COUNT; seat++) {
            room.seatAi(OWNER, SeatNo.of(seat), "agent_" + seat, ctx(3 + seat));
        }
        assertTrue(room.allReady());

        room.startGame(OWNER, "g_0001", ctx(30));
        assertEquals(RoomStatus.RUNNING, room.status());
        assertEquals("g_0001", room.gameId());
    }

    @Test
    @DisplayName("开局后禁止入座和准备；真人退出保留座位并转 AI")
    void runningRoomRejectsSeatChanges() {
        Room room = newRoom();
        room.ready(OWNER, ctx(3));
        for (int seat = 2; seat <= Room.SEAT_COUNT; seat++) {
            room.seatAi(OWNER, SeatNo.of(seat), "agent_" + seat, ctx(3 + seat));
        }
        room.startGame(OWNER, "g_0001", ctx(30));

        assertEquals(DomainErrorCode.GAME_ALREADY_STARTED,
                assertThrows(GameException.class, () -> room.join(ALICE, ctx(31))).code());
        assertEquals(DomainErrorCode.GAME_ALREADY_STARTED,
                assertThrows(GameException.class, () -> room.ready(OWNER, ctx(32))).code());
        room.leave(OWNER, ctx(33));
        assertEquals(RoomStatus.CLOSED, room.status());
        assertTrue(room.seatAt(SeatNo.of(1)).isAi());
        assertEquals("NO_HUMANS", room.closedReason());
    }

    @Test
    @DisplayName("INV-13/INV-16：命令路径与重放路径得到完全相同的状态")
    void liveStateEqualsReplayedState() {
        Room live = newRoom();
        live.join(ALICE, ctx(2));
        live.join(BOB, ctx(3));
        live.seatAi(OWNER, SeatNo.of(4), "agent_04", ctx(4));
        live.ready(ALICE, ctx(5));
        live.leave(BOB, ctx(6));
        live.clearAi(OWNER, SeatNo.of(4), ctx(7));

        // 重放同一批事件：apply 是唯一改状态的地方，因此必须逐字段一致。
        Room replayed = Room.create(RoomId.of("r_0001"));
        for (DomainEvent event : live.pendingEvents()) {
            replayed.replay(event);
        }

        assertEquals(live.version(), replayed.version());
        assertEquals(live.name(), replayed.name());
        assertEquals(live.ownerId(), replayed.ownerId());
        assertEquals(live.status(), replayed.status());
        assertEquals(live.config(), replayed.config());
        assertEquals(live.seats(), replayed.seats(), "座位必须逐字段一致");
        assertEquals(live.occupiedCount(), replayed.occupiedCount());
        assertEquals(live.readyCount(), replayed.readyCount());
    }

    @Test
    @DisplayName("关闭房间：状态转 CLOSED，之后不再接受变更")
    void closedRoomRejectsChanges() {
        Room room = newRoom();
        room.close(OWNER, "MANUAL", ctx(2));
        assertEquals(RoomStatus.CLOSED, room.status());

        assertEquals(DomainErrorCode.ROOM_CLOSED,
                assertThrows(GameException.class, () -> room.join(ALICE, ctx(3))).code());
        // 重复关闭幂等：不产生第二个 ROOM_CLOSED。
        int before = room.pendingEvents().size();
        room.close(OWNER, "MANUAL", ctx(4));
        assertEquals(before, room.pendingEvents().size());
    }

    @Test
    @DisplayName("房间事件元数据用 GAME_INIT 与 round=1（EventMeta 强制非空、≥1）")
    void roomEventMetaUsesGameInit() {
        Room room = newRoom();
        for (DomainEvent event : room.pendingEvents()) {
            assertEquals(com.wolfmind.domain.game.model.GamePhase.GAME_INIT, event.meta().phase());
            assertEquals(1, event.meta().round());
            assertNotNull(event.meta().eventId());
        }
    }
}
