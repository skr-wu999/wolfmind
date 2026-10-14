package com.wolfmind.domain.room.model;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.room.event.PlayerLeftEvent;
import com.wolfmind.domain.room.event.PlayerReadyEvent;
import com.wolfmind.domain.room.event.PlayerSeatedEvent;
import com.wolfmind.domain.room.event.RoomClosedEvent;
import com.wolfmind.domain.room.event.RoomControllerChangedEvent;
import com.wolfmind.domain.room.event.RoomCreatedEvent;
import com.wolfmind.domain.room.event.RoomStartedEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 房间聚合（ddd-design.md §2.1、§4.3 的 {@code Room}）。
 *
 * <p><b>它为什么存在</b>：大厅与等待页描述的是"开局之前"的状态——谁坐在几号位、谁准备好了、
 * 房主是谁、用什么配置开局。这些都不是 {@code Game} 的职责：对局一旦开始，座位与就绪就不再变化。
 * 把两者塞进一个聚合会让"开局"这个动作同时改两类不变量，无法保证哪一半失败时回滚。
 *
 * <p>不变量（ddd-design.md §4.3）：
 * <ul>
 *   <li><b>INV-R1</b> 座位数固定 9 个，同一座位同一时刻至多一个玩家；</li>
 *   <li><b>INV-R2</b> 只有全部座位就绪（真人 ready、AI 座位自动就绪）才能开局；</li>
 *   <li><b>INV-R3</b> 房间状态变化（创建、入座、就绪、离开、AI 补位）全部产生事件。</li>
 * </ul>
 *
 * <p><b>INV-13/INV-16（改状态只在 apply）</b>：命令方法只做校验 + 产出事件，
 * 真正的字段赋值全部发生在 {@code apply(event)}，因此"实时处理"与"重放事件流"必然得到同一份状态。
 *
 * <p><b>关于事件元数据里的 {@code phase}</b>：{@code EventMeta} 强制要求 phase 非空、
 * round ≥ 1，而房间事件本不属于任何对局阶段。房间事件统一用
 * {@link GamePhase#GAME_INIT}（其定义就是"已创建聚合、尚未开局"）与 {@code round = 1}。
 * 这点是无害的：房间事件的 {@code EventType.isGameEvent()} 为 false，
 * 因此不会被对局投影、阶段推进或可见性过滤当作游戏事件处理。
 */
public final class Room {

    /** INV-R1：经典九人局，座位数固定。 */
    public static final int SEAT_COUNT = 9;

    /** 房间事件元数据的轮次占位：房间不属于任何轮次，固定为 1 以满足 EventMeta 的契约。 */
    private static final int ROOM_EVENT_ROUND = 1;

    private final RoomId roomId;
    private final List<RoomSeat> seats = new ArrayList<>();
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    private int version;
    private String name;
    private String ownerId;
    private RoomStatus status;
    private String gameId;
    private Map<String, Object> config = Map.of();
    private String closedReason;

    private Room(RoomId roomId) {
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        for (int seatNo = 1; seatNo <= SEAT_COUNT; seatNo++) {
            seats.add(RoomSeat.empty(SeatNo.of(seatNo)));
        }
    }

    public static Room create(RoomId roomId) {
        return new Room(roomId);
    }

    public RoomId roomId() {
        return roomId;
    }

    public int version() {
        return version;
    }

    public String name() {
        return name;
    }

    public String ownerId() {
        return ownerId;
    }

    public RoomStatus status() {
        return status;
    }

    public String gameId() {
        return gameId;
    }

    public Map<String, Object> config() {
        return config;
    }

    public String closedReason() {
        return closedReason;
    }

    public List<RoomSeat> seats() {
        return List.copyOf(seats);
    }

    public RoomSeat seatAt(SeatNo seatNo) {
        return seats.get(seatNo.value() - 1);
    }

    public List<DomainEvent> pendingEvents() {
        return List.copyOf(uncommittedEvents);
    }

    /** 已占用座位数。 */
    public int occupiedCount() {
        return (int) seats.stream().filter(RoomSeat::occupied).count();
    }

    /** 已就绪座位数；AI 座位入座即就绪，因此与"占用且 ready"等价。 */
    public int readyCount() {
        return (int) seats.stream().filter(RoomSeat::satisfied).count();
    }

    /** INV-R2：全部座位就绪且坐满，才能开局。 */
    public boolean allReady() {
        return occupiedCount() == SEAT_COUNT && readyCount() == SEAT_COUNT;
    }

    /** 该玩家所在的座位；不在房间里返回 {@code null}。 */
    public SeatNo seatOf(String playerId) {
        for (RoomSeat seat : seats) {
            if (seat.occupied() && seat.playerId().value().equals(playerId)) {
                return seat.seatNo();
            }
        }
        return null;
    }

    /** 第一个空座；没有空座返回 {@code null}。 */
    public SeatNo firstFreeSeat() {
        for (RoomSeat seat : seats) {
            if (!seat.occupied()) {
                return seat.seatNo();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    /** 创建房间：房主占 1 号座（api-spec.md §4 的 `201 {roomId, ownerId, seats[]}`）。 */
    public List<DomainEvent> create(
            String name, String ownerId, Map<String, Object> config, ActionContext ctx) {
        requireBlankName(name);
        requireOwner(ownerId);
        if (status != null) {
            throw GameException.Factory.alreadyStarted("房间已创建，不能重复创建");
        }
        emit(createdEvent(name, ownerId, config, ctx));
        // 房主直接入座 1 号，避免"创建后还要再点一次入座"这种无谓步骤。
        emit(seatedEvent(SeatNo.of(1), PlayerId.of(ownerId),
                new PlayerController(ControllerType.HUMAN, ownerId), ctx));
        return pendingEvents();
    }

    /**
     * 入座（自动分配最小空座，api-spec.md §4 的 `POST /rooms/{id}/join`）。
     *
     * <p>已在房间内时**幂等返回**：客户端重复点击或断线重连后重复入座不应报错。
     */
    public List<DomainEvent> join(String playerId, ActionContext ctx) {
        requireWaiting();
        SeatNo existing = seatOf(playerId);
        if (existing != null) {
            return List.of();
        }
        SeatNo target = firstFreeSeat();
        if (target == null) {
            throw GameException.Factory.roomFull();
        }
        emit(seatedEvent(target, PlayerId.of(playerId),
                new PlayerController(ControllerType.HUMAN, playerId), ctx));
        return pendingEvents();
    }

    /** 指定座位入座（api-spec.md §4 的 `POST /rooms/{id}/seats/{seatNo}/take`）。 */
    public List<DomainEvent> takeSeat(String playerId, SeatNo seatNo, ActionContext ctx) {
        requireWaiting();
        RoomSeat seat = seatAt(seatNo);
        if (seat.occupied()) {
            // 自己已经坐在这个位子上时视为幂等成功。
            if (seat.playerId().value().equals(playerId)) {
                return List.of();
            }
            throw GameException.Factory.alreadySeated("座位 " + seatNo.value() + " 已被占用");
        }
        SeatNo existing = seatOf(playerId);
        if (existing != null) {
            throw GameException.Factory.alreadySeated(
                    "已在 " + existing.value() + " 号座位，请先离开房间再换座");
        }
        emit(seatedEvent(seatNo, PlayerId.of(playerId),
                new PlayerController(ControllerType.HUMAN, playerId), ctx));
        return pendingEvents();
    }

    /** 离开房间；房主离开时**移交**给第一个真人座位（无真人则置空）。 */
    public List<DomainEvent> leave(String playerId, ActionContext ctx) {
        if (status == RoomStatus.CLOSED) return List.of();
        SeatNo seat = seatOf(playerId);
        if (seat == null) {
            // 幂等：不在房间内时离开是空操作。
            return List.of();
        }
        RoomSeat current = seatAt(seat);
        if (current.controller().type() != ControllerType.HUMAN) return List.of();
        if (status == RoomStatus.RUNNING) {
            emit(new RoomControllerChangedEvent(meta(ctx), roomId, seat.value(), ControllerType.HUMAN,
                    current.controller().refId(), ControllerType.AI, "departed:" + playerId));
        } else {
            requireWaiting();
            emit(new PlayerLeftEvent(meta(ctx), roomId, PlayerId.of(playerId), seat.value()));
        }
        if (humanCount() == 0) emit(new RoomClosedEvent(meta(ctx), roomId, "NO_HUMANS"));
        return pendingEvents();
    }

    public int humanCount() {
        return (int) seats.stream().filter(seat -> seat.occupied()
                && seat.controller().type() == ControllerType.HUMAN).count();
    }

    public List<DomainEvent> closeSystem(String reason, ActionContext ctx) {
        if (!ctx.isSystem()) throw GameException.Factory.forbidden("房间自动关闭仅允许系统调用");
        if (status == RoomStatus.CLOSED) return List.of();
        emit(new RoomClosedEvent(meta(ctx), roomId, reason));
        return pendingEvents();
    }

    /** 准备（只有置位语义，见 {@code PlayerReadyEvent} 的说明）。 */
    public List<DomainEvent> ready(String playerId, ActionContext ctx) {
        requireWaiting();
        SeatNo seat = seatOf(playerId);
        if (seat == null) {
            throw GameException.Factory.notSeated("请先入座，再准备");
        }
        if (seatAt(seat).ready()) {
            // 幂等：重复准备不产生第二个事件，否则就绪计数会被重复触发。
            return List.of();
        }
        emit(new PlayerReadyEvent(meta(ctx), roomId, PlayerId.of(playerId)));
        return pendingEvents();
    }

    /**
     * 把座位交给 AI（api-spec.md §4 的 `POST .../seats/{seatNo}/ai`）。
     *
     * <p>只有房主能操作，且不能覆盖真人座位。AI 座位入座即就绪（INV-R2 的推论）。
     */
    public List<DomainEvent> seatAi(
            String actorId, SeatNo seatNo, String agentConfigId, ActionContext ctx) {
        requireOwnerActor(actorId);
        requireWaiting();
        RoomSeat seat = seatAt(seatNo);
        if (seat.occupied() && !seat.isAi()) {
            throw GameException.Factory.alreadySeated(
                    "座位 " + seatNo.value() + " 上是真人玩家，不能改为 AI");
        }
        // AI 座位的 playerId 与控制者 refId 都用 agentConfigId：Agent Runtime 按
        // (gameId, playerId) 建实例，两者一致才能让"提交行动"的身份反查成立。
        PlayerId aiPlayerId = PlayerId.of(agentConfigId);
        if (seat.occupied() && seat.playerId().equals(aiPlayerId)) {
            return List.of();
        }
        if (seat.occupied()) {
            emit(new PlayerLeftEvent(meta(ctx), roomId, seat.playerId(), seatNo.value()));
        }
        emit(seatedEvent(seatNo, aiPlayerId,
                new PlayerController(ControllerType.AI, agentConfigId), ctx));
        return pendingEvents();
    }

    /** 移出座位的 AI，座位回到空置（api-spec.md §4 的 `DELETE .../seats/{seatNo}/ai`）。 */
    public List<DomainEvent> clearAi(String actorId, SeatNo seatNo, ActionContext ctx) {
        requireOwnerActor(actorId);
        requireWaiting();
        RoomSeat seat = seatAt(seatNo);
        if (!seat.occupied()) {
            return List.of();
        }
        requireAiSeat(seat, seatNo);
        ControllerType fromType = seat.controller().type();
        String fromRefId = seat.controller().refId();
        emit(new RoomControllerChangedEvent(
                meta(ctx), roomId, seatNo.value(), fromType, fromRefId, null, null));
        emit(new PlayerLeftEvent(meta(ctx), roomId, seat.playerId(), seatNo.value()));
        return pendingEvents();
    }

    /** 开局：座位冻结、状态转 RUNNING、记录 gameId。 */
    public List<DomainEvent> startGame(String actorId, String startedGameId, ActionContext ctx) {
        requireOwnerActor(actorId);
        requireWaiting();
        if (!allReady()) {
            throw GameException.Factory.notReady(
                    "还有 " + (SEAT_COUNT - readyCount()) + " 个座位未就绪，不能开局");
        }
        // 必须产出事件：status 与 gameId 的变化只有进了事件流才能在重放/重启后存活。
        // 直接赋值会让"房间已开局"只存在于内存——重启后重放事件流得到"还在等待"，
        // 座位重新可改，而那个对局已经在跑。
        emit(new RoomStartedEvent(meta(ctx), roomId, startedGameId));
        return pendingEvents();
    }

    /** 关闭房间。 */
    public List<DomainEvent> close(String actorId, String reason, ActionContext ctx) {
        requireOwnerActor(actorId);
        if (status == RoomStatus.CLOSED) {
            return List.of();
        }
        emit(new RoomClosedEvent(meta(ctx), roomId, reason));
        return pendingEvents();
    }

    /** 按提交顺序重放事件（恢复路径）。 */
    public void replay(DomainEvent event) {
        apply(event);
    }

    // ------------------------------------------------------------------
    // 事件构造
    // ------------------------------------------------------------------

    private RoomCreatedEvent createdEvent(
            String name, String ownerId, Map<String, Object> config, ActionContext ctx) {
        return new RoomCreatedEvent(
                meta(ctx), roomId, name, ownerId, config == null ? Map.of() : config);
    }

    private PlayerSeatedEvent seatedEvent(
            SeatNo seatNo, PlayerId playerId, PlayerController controller, ActionContext ctx) {
        return new PlayerSeatedEvent(
                meta(ctx), roomId, playerId, seatNo.value(), controller.type(), controller.refId());
    }

    /**
     * 房间事件的元数据。
     *
     * <p>{@code phase} 用 {@link GamePhase#GAME_INIT}：房间不属于任何对局阶段，而 GAME_INIT 的
     * 定义正是"已创建聚合、尚未开局"。{@code round} 固定 1 以满足 EventMeta 的 "round ≥ 1"。
     */
    private EventMeta meta(ActionContext ctx) {
        return new EventMeta(
                ctx.eventIdFor(version + 1),
                version + 1,
                ctx.occurredAt(),
                GamePhase.GAME_INIT,
                ROOM_EVENT_ROUND,
                ctx.actorId(),
                ctx.commandId(),
                ctx.correlationId());
    }

    private <T extends DomainEvent> T emit(T event) {
        uncommittedEvents.add(event);
        apply(event);
        return event;
    }

    // ------------------------------------------------------------------
    // 应用（唯一改状态的地方，INV-13/INV-16）
    // ------------------------------------------------------------------

    private void apply(DomainEvent event) {
        switch (event) {
            case RoomCreatedEvent created -> applyCreated(created);
            case PlayerSeatedEvent seated -> applySeated(seated);
            case PlayerLeftEvent left -> applyLeft(left);
            case PlayerReadyEvent readyEvent -> applyReady(readyEvent);
            case RoomStartedEvent started -> applyStarted(started);
            case RoomControllerChangedEvent changed -> applyControllerChanged(changed);
            case RoomClosedEvent closed -> applyClosed(closed);
            default -> throw new IllegalStateException(
                    "Room 聚合不认识事件 " + event.type() + "（回放路径不得静默忽略）");
        }
        version = event.meta().version();
    }

    private void applyCreated(RoomCreatedEvent event) {
        this.name = event.name();
        this.ownerId = event.ownerId();
        this.config = event.config();
        this.status = RoomStatus.WAITING;
    }

    private void applySeated(PlayerSeatedEvent event) {
        SeatNo seatNo = SeatNo.of(event.seatNo());
        ControllerType type = event.controllerType();
        // AI 座位入座即就绪：不需要额外的 PLAYER_READY，否则"AI 补位后还要等准备"会卡住开局。
        boolean autoReady = type == ControllerType.AI;
        seats.set(seatNo.value() - 1, new RoomSeat(
                seatNo, event.playerId(),
                new PlayerController(type, event.refId()),
                autoReady,
                event.meta().occurredAt()));
    }

    private void applyLeft(PlayerLeftEvent event) {
        SeatNo seatNo = SeatNo.of(event.seatNo());
        seats.set(seatNo.value() - 1, RoomSeat.empty(seatNo));
        if (event.playerId().value().equals(ownerId)) applyOwnerHandover();
    }

    /** 开局：冻结座位并绑定对局。唯一的赋值点，因此重放路径与实时路径一致。 */
    private void applyStarted(RoomStartedEvent event) {
        this.status = RoomStatus.RUNNING;
        this.gameId = event.gameId();
    }

    private void applyReady(PlayerReadyEvent event) {
        // 准备事件只带 playerId，需要反查座位：这样事件本身小，而"哪个座位"可由状态唯一确定。
        for (int index = 0; index < seats.size(); index++) {
            RoomSeat seat = seats.get(index);
            if (seat.occupied() && seat.playerId().equals(event.playerId())) {
                seats.set(index, new RoomSeat(
                        seat.seatNo(), seat.playerId(), seat.controller(), true, seat.joinedAt()));
                return;
            }
        }
        throw new IllegalStateException(
                "PLAYER_READY 找不到座位：" + event.playerId().value() + "（事件流与状态不一致）");
    }

    private void applyControllerChanged(RoomControllerChangedEvent event) {
        // 房间流形态只到座位粒度：把控制者换成新的（toType 为空表示空置，由 PLAYER_LEFT 收尾）。
        SeatNo seatNo = SeatNo.of(event.seatNo());
        RoomSeat seat = seats.get(seatNo.value() - 1);
        if (event.toType() == null) {
            return;
        }
        seats.set(seatNo.value() - 1, new RoomSeat(
                seatNo,
                seat.playerId() == null ? PlayerId.of(event.toRefId()) : seat.playerId(),
                new PlayerController(event.toType(), event.toRefId()),
                seat.ready(),
                seat.joinedAt()));
        if (seat.playerId() != null && seat.playerId().value().equals(ownerId)
                && event.toType() != ControllerType.HUMAN) applyOwnerHandover();
    }

    private void applyClosed(RoomClosedEvent event) {
        this.status = RoomStatus.CLOSED;
        this.closedReason = event.reason();
    }

    /** 房主离席后的移交：给第一个真人座位；无人则置空（房间仍存在，只是暂时无主）。 */
    private void applyOwnerHandover() {
        for (RoomSeat seat : seats) {
            if (seat.occupied() && !seat.isAi()) {
                this.ownerId = seat.playerId().value();
                return;
            }
        }
        this.ownerId = null;
    }

    // ------------------------------------------------------------------
    // 前置校验
    // ------------------------------------------------------------------

    private void requireWaiting() {
        if (status == RoomStatus.RUNNING) {
            throw GameException.Factory.alreadyStarted("对局已开始，座位与准备状态已冻结");
        }
        if (status == RoomStatus.CLOSED) {
            throw GameException.Factory.roomClosed("房间已关闭");
        }
    }

    private void requireOwnerActor(String actorId) {
        if (ownerId == null) {
            throw GameException.Factory.forbidden("房间当前没有房主，无法执行该操作");
        }
        if (!ownerId.equals(actorId)) {
            throw GameException.Factory.forbidden("只有房主可以执行该操作");
        }
    }

    private static void requireAiSeat(RoomSeat seat, SeatNo seatNo) {
        if (!seat.isAi()) {
            throw GameException.Factory.notAllowed(
                    "座位 " + seatNo.value() + " 不是 AI 座位，不能移出 AI");
        }
    }

    private static void requireBlankName(String name) {
        if (name == null || name.isBlank()) {
            throw GameException.Factory.validation("房间名称不能为空");
        }
        if (name.length() > 24) {
            throw GameException.Factory.validation("房间名称最多 24 个字符");
        }
    }

    private static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw GameException.Factory.validation("房主不能为空");
        }
    }

    /** 房间配置的深拷贝视图；供查询层构造响应。 */
    public Map<String, Object> configView() {
        return new LinkedHashMap<>(config);
    }
}
