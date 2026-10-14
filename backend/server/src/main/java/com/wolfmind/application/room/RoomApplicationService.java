package com.wolfmind.application.room;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.room.model.Room;
import com.wolfmind.domain.room.model.RoomId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 房间用例服务：创建、入座、离开、准备、AI 补位、开局冻结、关闭（api-spec.md 4）。
 *
 * <p>与 {@code SubmitActionApplicationService} 保持同一套骨架：幂等/锁 → 加载 → 校验 →
 * 领域命令 → 提交 → 发布。顺序不可调换：<b>先持久化再发布</b>，
 * 否则客户端可能收到一个后来没有落库的状态（DESIGN.md 6）。
 *
 * <p>这里**不含任何规则判断**——能不能入座、能不能开局全部在 {@link Room} 里。
 * 用例服务只提供身份、版本、锁与时间（C-3）。
 */
public final class RoomApplicationService {

    /** 房间锁的键前缀；与对局锁分开，因为它们是两个聚合。 */
    private static final String LOCK_PREFIX = "room:";

    private final RoomRepositoryPort repository;
    private final RoomIndexPort index;
    private final GameLockPort lock;
    private final EventPublisherPort publisher;
    private final TimePort time;
    private final RoomIdGenerator idGenerator = new RoomIdGenerator();

    public RoomApplicationService(
            RoomRepositoryPort repository,
            RoomIndexPort index,
            GameLockPort lock,
            EventPublisherPort publisher,
            TimePort time) {
        this.repository = repository;
        this.index = index;
        this.lock = lock;
        this.publisher = publisher;
        this.time = time;
    }

    /** 创建房间：房主自动占 1 号座（`POST /rooms`）。 */
    public RoomView create(String actorId, String name, Map<String, Object> config, String idempotencyKey) {
        com.wolfmind.application.game.GameRulesCodec.fromRoomConfig(config);
        return write(null, actorId, idempotencyKey, (room, ctx) ->
                room.create(name, actorId, config, ctx));
    }

    /** 入座，自动分配最小空座（`POST /rooms/{id}/join`）。 */
    public RoomView join(String roomId, String actorId, String idempotencyKey, String correlationId) {
        return write(roomId, actorId, idempotencyKey, (room, ctx) -> room.join(actorId, ctx), correlationId);
    }

    /** 指定座位（`POST /rooms/{id}/seats/{seatNo}/take`）。 */
    public RoomView takeSeat(String roomId, String actorId, int seatNo, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey,
                (room, ctx) -> room.takeSeat(actorId, SeatNo.of(seatNo), ctx));
    }

    /** 离开（`POST /rooms/{id}/leave`）。 */
    public RoomView leave(String roomId, String actorId, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey, (room, ctx) -> room.leave(actorId, ctx));
    }

    public RoomView leaveIfOffline(String roomId, String actorId, String key, RoomPresencePort presence) {
        return write(roomId, actorId, key, (room, ctx) -> presence.present(roomId, room.gameId(), actorId)
                ? List.of() : room.leave(actorId, ctx));
    }

    /** 准备（`POST /rooms/{id}/ready`）。 */
    public RoomView ready(String roomId, String actorId, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey, (room, ctx) -> room.ready(actorId, ctx));
    }

    /** 把座位交给 AI（`POST /rooms/{id}/seats/{seatNo}/ai`）。 */
    public RoomView seatAi(String roomId, String actorId, int seatNo, String agentConfigId, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey,
                (room, ctx) -> room.seatAi(actorId, SeatNo.of(seatNo), agentConfigId, ctx));
    }

    /** 移出 AI（`DELETE /rooms/{id}/seats/{seatNo}/ai`）。 */
    public RoomView clearAi(String roomId, String actorId, int seatNo, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey,
                (room, ctx) -> room.clearAi(actorId, SeatNo.of(seatNo), ctx));
    }

    /**
     * 开局冻结（`POST /rooms/{id}/games` 的第 1 步）。
     *
     * <p>注意它**只把房间状态转为 RUNNING 并记录 gameId**，不创建对局；
     * 对局的创建与 AI 实例化由 {@code StartGameApplicationService} 负责。
     * 分成两步是因为两者属于不同聚合：房间的"已开局"是冻结座位，对局的"已开始"是发牌。
     */
    public RoomView startGame(String roomId, String actorId, String gameId, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey, (room, ctx) -> room.startGame(actorId, gameId, ctx));
    }

    /** 关闭房间（`POST /rooms/{id}/leave` 且房主离开、或显式关闭）。 */
    public RoomView close(String roomId, String actorId, String reason, String idempotencyKey) {
        return write(roomId, actorId, idempotencyKey, (room, ctx) -> room.close(actorId, reason, ctx));
    }

    public RoomView closeSystem(String roomId, String reason) {
        return write(roomId, ActionContext.SYSTEM_ACTOR, "room-close:" + roomId,
                (room, ctx) -> room.closeSystem(reason, ctx));
    }

    /** 只读加载；不存在时抛 {@code ROOM_NOT_FOUND}。 */
    public Room load(String roomId) {
        if (roomId == null || roomId.isBlank() || !repository.exists(roomId)) {
            throw GameException.Factory.roomNotFound(String.valueOf(roomId));
        }
        return repository.load(roomId).room();
    }

    // ------------------------------------------------------------------

    /** 房间命令的统一骨架：锁 → 加载 → 领域命令 → 提交 → 发布。 */
    private RoomView write(
            String roomId, String actorId, String idempotencyKey, RoomCommand command) {
        return write(roomId, actorId, idempotencyKey, command, idempotencyKey);
    }

    @FunctionalInterface
    private interface RoomCommand {
        List<DomainEvent> apply(Room room, ActionContext ctx);
    }

    private RoomView write(
            String roomId, String actorId, String idempotencyKey, RoomCommand command,
            String correlationId) {
        if (actorId == null || actorId.isBlank()) {
            throw GameException.Factory.validation("actorId 不能为空");
        }
        // 创建时还没有房间号；其余命令必须有。这里统一在锁之前校验，
        // 让"房间不存在"与"房间正忙"两种失败可区分。
        boolean creating = roomId == null || roomId.isBlank();
        // 用 final 变量承载最终房间号：下面的 lambda 需要捕获它，而上面的分支会重新赋值。
        final String targetRoomId;
        if (creating) {
            targetRoomId = idGenerator.next(time);
        } else if (!index.contains(roomId)) {
            throw GameException.Factory.roomNotFound(roomId);
        } else {
            targetRoomId = roomId;
        }

        String lockKey = LOCK_PREFIX + targetRoomId;
        GameLockPort.LockToken token = lock.tryLock(lockKey)
                .orElseThrow(() -> GameException.Factory.busy(
                        com.wolfmind.domain.game.model.GameId.of(targetRoomId)));
        try {
            RoomRepositoryPort.LoadedRoom loaded = repository.load(targetRoomId);
            Room room = loaded.room();
            ActionContext ctx = context(
                    targetRoomId, actorId, idempotencyKey, correlationId, loaded.version(), creating);
            command.apply(room, ctx);

            List<DomainEvent> events = room.pendingEvents();
            List<EventEnvelope> envelopes = new ArrayList<>(events.size());
            int version = loaded.version();
            for (DomainEvent event : events) {
                envelopes.add(EventEnvelope.of(event, streamName(targetRoomId), ++version));
            }
            repository.commit(targetRoomId, loaded.version(), envelopes);
            // 索引在建房提交成功之后登记。顺序不能反：先登记再提交会留下"索引里有、事件流没有"
            // 的幽灵房间——客户端能看到它，但里面 9 个座位全空，永远无法开局。
            if (creating && !envelopes.isEmpty()) {
                index.add(targetRoomId);
            }
            if (!envelopes.isEmpty()) {
                publisher.publishRealtime(envelopes);
            }
            return RoomView.of(room);
        } finally {
            lock.unlock(token);
        }
    }

    /**
     * 房间事件的元数据上下文。
     *
     * <p>创建时 expectedVersion 为 0（流不存在）；其余命令用加载到的版本，
     * 因此"基于过期状态写入"会在事件存储的版本前置校验处失败（{@code GAME_VERSION_CONFLICT}）。
     */
    private ActionContext context(
            String roomId, String actorId, String idempotencyKey, String correlationId,
            int version, boolean creating) {
        long occurredAt = time.nowMillis();
        String commandId = idempotencyKey == null || idempotencyKey.isBlank()
                ? "room_" + roomId + "_" + version
                : idempotencyKey;
        return ActionContext.of(
                "ev_" + roomId + "_" + (creating ? 1 : version + 1),
                occurredAt,
                actorId,
                commandId,
                correlationId == null ? commandId : correlationId,
                creating ? 0 : version);
    }

    /**
     * 房间事件的流派名。
     *
     * <p>取自端口常量而不是某个实现类：应用层不得依赖基础设施，
     * 而且这个约定必须与实现共用一份（否则房间会加载不到自己刚写的事件）。
     */
    private static String streamName(String roomId) {
        return RoomRepositoryPort.streamName(roomId);
    }
}
