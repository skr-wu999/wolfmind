package com.wolfmind.infrastructure;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.StartGameCommand;
import com.wolfmind.domain.game.model.Visibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础设施测试共用的事件夹具。
 *
 * <p>放在 {@code infrastructure} 包（而不是某一个子包的测试目录）是为了让 memory 与 sqlite
 * 两组测试共用同一份事件构造逻辑：事件信封的形状属于基础设施契约，不属于某个适配器。
 *
 * <p>开局流来自真正的 {@link Game#start}，因为"快照恢复等价性"只有在领域聚合真的能重放这些
 * 事件时才有意义；手写假事件会让测试在领域契约变化后仍然变绿。
 */
public final class InfrastructureTestEvents {

    public static final long BASE_TIME = 1_730_000_000_000L;

    private InfrastructureTestEvents() {}

    /** 九人标准局的座位分配：全部 HUMAN、全部就绪。 */
    public static List<SeatAssignment> standardSeats() {
        List<SeatAssignment> seats = new ArrayList<>();
        for (int seatNo = 1; seatNo <= 9; seatNo++) {
            seats.add(new SeatAssignment(SeatNo.of(seatNo), PlayerController.human("u_" + seatNo), true));
        }
        return seats;
    }

    public static StartGameCommand startCommand(String gameId) {
        return new StartGameCommand(
                GameId.of(gameId), "room_1", standardSeats(), RoleComposition.STANDARD_NINE, GameRules.DEFAULT);
    }

    /** 带版本前置校验的命令上下文。 */
    public static ActionContext context(int expectedVersion) {
        return new ActionContext(
                "cmd_" + expectedVersion, BASE_TIME + expectedVersion, "SYSTEM", "cmd_" + expectedVersion,
                "cor_test", expectedVersion, 42L);
    }

    /** 真实开局产生的 11 条事件，按版本 1..11 包成信封。 */
    public static List<EventEnvelope> startEventStream(String gameId) {
        Game game = Game.create(GameId.of(gameId));
        List<DomainEvent> events = game.start(startCommand(gameId), context(0));
        List<EventEnvelope> envelopes = new ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) {
            envelopes.add(EventEnvelope.of(events.get(index), gameId, index + 1));
        }
        return envelopes;
    }

    /** 一条字段完整、payload 极简的信封，用于版本语义与批量路径测试。 */
    public static EventEnvelope bareEnvelope(String gameId, int version, EventType type) {
        return new EventEnvelope(
                gameId + "-" + version, gameId, version, type.code(), 1,
                BASE_TIME + version, GamePhase.NIGHT_WOLF.name(), 1, "SYSTEM", Visibility.PUBLIC,
                "cmd_" + version, "cor_test", Map.of("version", version));
    }

    /**
     * 一条 {@code payload} 显式含 null 值的信封。
     *
     * <p>空刀与弃权票按契约就是可空且有语义的字段：编码时丢掉 null 键会让解码方看到"缺字段"，
     * 而契约要求缺字段必须失败。
     */
    public static EventEnvelope envelopeWithNullPayload(String gameId, int version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", "p_3");
        payload.put("targetId", null);
        payload.put("abstain", true);
        return new EventEnvelope(
                gameId + "-" + version, gameId, version, EventType.PLAYER_VOTED.code(), 1,
                BASE_TIME + version, GamePhase.DAY_VOTE.name(), 2, "p_3", Visibility.PUBLIC,
                "cmd_" + version, "cor_test", payload);
    }

    /** 复用某条信封的元数据，构造同流的第 {@code version} 版信封。 */
    public static EventMeta metaOf(EventEnvelope template, int version) {
        return new EventMeta(
                template.gameId() + "-" + version, version, BASE_TIME + version,
                GamePhase.valueOf(template.phase()), template.round(), template.actorId(),
                template.causationId(), template.correlationId());
    }
}
