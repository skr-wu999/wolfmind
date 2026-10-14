package com.wolfmind.application.room;

import com.wolfmind.application.game.EventEnvelopes;
import com.wolfmind.application.game.EventPublication;
import com.wolfmind.application.port.*;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.domain.room.model.Room;
import com.wolfmind.domain.room.model.RoomStatus;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable room departure first, then idempotent game takeover/abort; periodic checks repair either step. */
public final class RoomLifecycleService {
    private final RoomApplicationService rooms;
    private final RoomIndexPort index;
    private final GameRepositoryPort games;
    private final EventStorePort events;
    private final GameLockPort locks;
    private final EventPublisherPort publisher;
    private final AgentProvisioningPort agents;
    private final RoomPresencePort presence;
    private final TimePort time;
    private final MetricsPort metrics;
    private final long grace;
    private record Member(String roomId, String userId) {}
    private final Map<Member, Long> offlineSince = new ConcurrentHashMap<>();

    public RoomLifecycleService(RoomApplicationService rooms, RoomIndexPort index, GameRepositoryPort games,
            GameLockPort locks, EventPublisherPort publisher, AgentProvisioningPort agents,
            RoomPresencePort presence, TimePort time, MetricsPort metrics, long grace, EventStorePort events) {
        if (grace < 1) throw new IllegalArgumentException("disconnect grace must be positive");
        this.rooms=rooms; this.index=index; this.games=games; this.locks=locks; this.publisher=publisher;
        this.agents=agents; this.presence=presence; this.time=time; this.metrics=metrics; this.grace=grace;
        this.events=events;
    }

    public RoomView leaveRoom(String roomId, String userId, String key) {
        rooms.leave(roomId, userId, key);
        reconcileRoom(roomId);
        offlineSince.remove(new Member(roomId, userId));
        return RoomView.of(rooms.load(roomId));
    }

    public RoomView leaveGame(String gameId, String userId, String key) {
        String roomId = games.load(gameId).game().snapshot().roomId();
        if (roomId == null || !index.contains(roomId))
            throw GameException.Factory.forbidden("这局不属于可退出的玩家房间");
        Room room = rooms.load(roomId);
        var seatNo = room.seatOf(userId);
        if (!gameId.equals(room.gameId()) || seatNo == null)
            throw GameException.Factory.forbidden("只能退出自己参与的房间");
        var controller = room.seatAt(seatNo).controller();
        if (!((controller.type() == ControllerType.HUMAN && userId.equals(controller.refId()))
                || (controller.type() == ControllerType.AI && ("departed:" + userId).equals(controller.refId()))))
            throw GameException.Factory.forbidden("只能退出自己的真人座位");
        return leaveRoom(roomId, userId, key);
    }

    public void onCommitted(List<EventEnvelope> events) {
        events.stream().filter(e -> e.type().equals("GAME_FINISHED") || e.type().equals("GAME_ABORTED"))
                .map(EventEnvelope::gameId).distinct().forEach(this::gameEnded);
    }

    public void gameEnded(String gameId) {
        var state = games.load(gameId).game().snapshot();
        if (state.status() != GameStatus.FINISHED && state.status() != GameStatus.ABORTED) return;
        String roomId = state.roomId();
        if (roomId != null && index.contains(roomId)) {
            Room room = rooms.load(roomId);
            if (gameId.equals(room.gameId())) rooms.closeSystem(roomId, "GAME_ENDED");
            offlineSince.keySet().removeIf(key -> key.roomId().equals(roomId));
        }
    }

    public void sweep() {
        Set<String> existing = new HashSet<>(index.all());
        offlineSince.keySet().removeIf(key -> !existing.contains(key.roomId()));
        for (String id : existing) {
            try {
                reconcileRoom(id);
                Room room = rooms.load(id);
                if (room.status() == RoomStatus.CLOSED) {
                    offlineSince.keySet().removeIf(key -> key.roomId().equals(id));
                    continue;
                }
                Set<String> members = new HashSet<>();
                for (var seat : room.seats()) {
                    if (!seat.occupied() || seat.controller().type() != ControllerType.HUMAN) continue;
                    String user = seat.controller().refId(); members.add(user);
                    Member member = new Member(id, user);
                    if (presence.present(id, room.gameId(), user)) offlineSince.remove(member);
                    else {
                        long since = offlineSince.computeIfAbsent(member, ignored -> time.nowMillis());
                        if (time.nowMillis() - since >= grace) {
                            rooms.leaveIfOffline(id,user,"offline:" + id + ":" + user,presence);
                            reconcileRoom(id);
                        }
                    }
                }
                offlineSince.keySet().removeIf(key -> key.roomId().equals(id) && !members.contains(key.userId()));
            } catch (RuntimeException failure) {
                metrics.counter("wolfmind.room.lifecycle.failures", 1);
                java.util.logging.Logger.getLogger(RoomLifecycleService.class.getName()).log(
                        java.util.logging.Level.WARNING, "房间检查失败，将补做：" + id, failure);
            }
        }
        metrics.gauge("wolfmind.room.offline.members", offlineSince.size());
    }

    public void reconcileRoom(String roomId) {
        Room room = rooms.load(roomId);
        if (room.gameId() == null) return;
        String gameId = room.gameId();
        if (room.status() == RoomStatus.CLOSED) {
            int tail = events.currentVersion(gameId);
            if (tail == 0 || events.read(gameId,tail,tail).stream()
                    .anyMatch(e -> e.type().equals("GAME_FINISHED") || e.type().equals("GAME_ABORTED"))) return;
        }
        var token = locks.tryLock(gameId).orElseThrow(() -> GameException.Factory.busy(GameId.of(gameId)));
        List<EventEnvelope> committed = new ArrayList<>();
        List<AgentProvisioningPort.Seat> provision = new ArrayList<>();
        boolean terminal;
        try {
            Game game = games.load(gameId).game();
            if (game.status() == GameStatus.RUNNING) {
                if (room.status() == RoomStatus.CLOSED || room.humanCount() == 0) {
                    int version = game.version();
                    var events = game.abort(room.humanCount() == 0 ? "NO_HUMANS" : "ROOM_CLOSED", context(gameId, version));
                    var envelopes = EventEnvelopes.wrap(gameId, version + 1, events);
                    games.commit(gameId, version, envelopes, GameRepositoryPort.SnapshotPolicy.INTERVAL);
                    committed.addAll(envelopes);
                } else for (var seat : room.seats()) {
                    if (!seat.occupied() || !seat.isAi() || !seat.controller().refId().startsWith("departed:")) continue;
                    var player = game.snapshot().players().stream().filter(p -> p.seatNo().value() == seat.seatNo().value())
                            .findFirst().orElseThrow();
                    if (player.controller().type() != ControllerType.HUMAN) continue;
                    int version = game.version();
                    String playerId = player.playerId().value();
                    var events = game.changeController(seat.seatNo(), new PlayerController(ControllerType.AI, playerId),
                            context(gameId, version));
                    var envelopes = EventEnvelopes.wrap(gameId, version + 1, events);
                    games.commit(gameId, version, envelopes, GameRepositoryPort.SnapshotPolicy.INTERVAL);
                    committed.addAll(envelopes);
                    provision.add(new AgentProvisioningPort.Seat(playerId, seat.seatNo().value(), true));
                }
            }
            terminal = game.status() == GameStatus.FINISHED || game.status() == GameStatus.ABORTED;
        } finally { locks.unlock(token); }
        if (!provision.isEmpty()) agents.prepare(gameId, provision);
        if (!committed.isEmpty()) EventPublication.publish(publisher, committed);
        if (terminal) {
            rooms.closeSystem(roomId, "GAME_ENDED");
            offlineSince.keySet().removeIf(key -> key.roomId().equals(roomId));
        }
    }

    private ActionContext context(String gameId, int version) {
        String id = "room-lifecycle:" + gameId + ":" + (version + 1);
        return ActionContext.of(id, time.nowMillis(), ActionContext.SYSTEM_ACTOR, id, id, version);
    }
}
