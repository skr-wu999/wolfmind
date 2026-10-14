package com.wolfmind.domain.room.event;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.room.model.RoomId;

import java.util.Map;

/**
 * 房间事件流的编解码（`room:{id}:events`）。
 *
 * <p><b>为什么单独一个编解码器，而不是给 {@code EventCodec} 加分支</b>：
 * {@code EventCodec} 的 {@code decode} 第一件事就是断言"这是不是 Game 聚合事件"，
 * 并明确拒绝非 Game 事件。让它同时兼容两种聚合，会把"哪个流能出现哪些事件"这条边界
 * 从编译期/断言期推到运行期的分支判断里——而那条边界正是回放正确性的前提
 * （房间事件混进对局流会被阶段推进当成未知事件）。
 *
 * <p>两个编解码器共享 {@link EventEnvelope} 与 {@link EventMeta} 的形态，
 * 因此接口层与仓储层面对的都是同一种信封。
 */
public final class RoomEventCodec {

    private RoomEventCodec() {}

    /**
     * 由信封还原房间事件。
     *
     * @throws GameException {@code REPLAY_UNKNOWN_EVENT_TYPE}（不是房间事件）或 {@code VALIDATION_ERROR}
     */
    public static DomainEvent decode(EventEnvelope envelope) {
        EventType type = EventType.fromCode(envelope.type());
        boolean roomControllerChange = type == EventType.PLAYER_CONTROLLER_CHANGED
                && envelope.gameId().startsWith("room:") && !envelope.payload().containsKey("playerId");
        if (type.isGameEvent() && !roomControllerChange) {
            throw new GameException(
                    DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "信封 " + envelope.type() + " 不属于房间事件流");
        }
        EventMeta meta = metaOf(envelope);
        Map<String, Object> payload = envelope.payload();
        RoomId roomId = RoomId.of(requiredText(payload, "roomId"));

        return switch (type) {
            case ROOM_CREATED -> new RoomCreatedEvent(
                    meta, roomId,
                    text(payload, "name"),
                    requiredText(payload, "ownerId"),
                    objectMap(payload, "config"));
            case PLAYER_SEATED -> new PlayerSeatedEvent(
                    meta, roomId,
                    PlayerId.of(requiredText(payload, "playerId")),
                    integer(payload, "seatNo"),
                    controllerType(payload, "controllerType"),
                    text(payload, "refId"));
            case PLAYER_LEFT -> new PlayerLeftEvent(
                    meta, roomId,
                    PlayerId.of(requiredText(payload, "playerId")),
                    integer(payload, "seatNo"));
            case PLAYER_READY -> new PlayerReadyEvent(
                    meta, roomId,
                    PlayerId.of(requiredText(payload, "playerId")));
            case PLAYER_CONTROLLER_CHANGED -> new RoomControllerChangedEvent(
                    meta, roomId,
                    integer(payload, "seatNo"),
                    optionalControllerType(payload, "fromType"),
                    text(payload, "fromRefId"),
                    optionalControllerType(payload, "toType"),
                    text(payload, "refId"));
            case ROOM_STARTED -> new RoomStartedEvent(
                    meta, roomId, requiredText(payload, "gameId"));
            case ROOM_CLOSED -> new RoomClosedEvent(
                    meta, roomId, text(payload, "reason"));
            // USER_* 事件属于 user:{id}:events 流，不在这里解码。
            // 静默忽略会让"投递到了错误的流"变成一个看不见的数据错误。
            default -> throw new GameException(
                    DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "信封 " + envelope.type() + " 不是房间事件（USER_* 属于用户流）");
        };
    }

    /**
     * 房间事件的元数据还原。
     *
     * <p>房间流不存在"对局阶段"，写入时统一用 {@link GamePhase#GAME_INIT} 与 round=1
     * （见 {@code Room.meta}）。这里**不信任信封里的 phase**，而是固定用 GAME_INIT：
     * 否则一个被篡改或被其它聚合误写的信封会让房间事件带上无意义的阶段。
     */
    private static EventMeta metaOf(EventEnvelope envelope) {
        return new EventMeta(
                envelope.eventId(),
                envelope.version(),
                envelope.occurredAt(),
                GamePhase.GAME_INIT,
                1,
                envelope.actorId(),
                envelope.causationId(),
                envelope.correlationId());
    }

    // ---------------------------------------------------------------- 取值助手

    private static String requiredText(Map<String, Object> payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            throw GameException.Factory.validation("房间事件缺少字段 " + field);
        }
        return value;
    }

    private static String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        throw GameException.Factory.validation("字段 " + field + " 期望字符串，实际为 " + value.getClass().getSimpleName());
    }

    private static int integer(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw GameException.Factory.validation("字段 " + field + " 期望整数");
    }

    private static ControllerType controllerType(Map<String, Object> payload, String field) {
        ControllerType type = optionalControllerType(payload, field);
        if (type == null) {
            throw GameException.Factory.validation("字段 " + field + " 不能为空");
        }
        return type;
    }

    private static ControllerType optionalControllerType(Map<String, Object> payload, String field) {
        String name = text(payload, field);
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ControllerType.valueOf(name);
        } catch (IllegalArgumentException error) {
            throw GameException.Factory.validation("字段 " + field + " 不是合法的控制者类型：" + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw GameException.Factory.validation("字段 " + field + " 期望对象");
    }
}
