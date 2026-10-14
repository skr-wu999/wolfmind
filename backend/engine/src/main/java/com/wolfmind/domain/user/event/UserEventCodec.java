package com.wolfmind.domain.user.event;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.user.model.UserId;

import java.util.Map;

/**
 * 账号事件流的编解码（{@code user:{id}:events}）。
 *
 * <p>与 {@link com.wolfmind.domain.room.event.RoomEventCodec} 同样的理由独立成类：
 * {@code EventCodec} 的第一件事就是断言"这是 Game 事件"，让它同时兼容三种聚合，
 * 会把"哪个流能出现哪些事件"从断言期推到运行期的分支判断里——而那条边界正是回放正确性的前提。
 *
 * <p><b>它永远不会解出凭据</b>：payload 契约里只有 {@code userId / username / sessionId / at}
 * （event-design.md §7.3）。口令散列、盐、会话令牌都不在事件流里，
 * 因此这个编解码器在结构上就不可能泄漏它们。
 */
public final class UserEventCodec {

    private UserEventCodec() {}

    /**
     * 由信封还原账号事件。
     *
     * @throws GameException {@code REPLAY_UNKNOWN_EVENT_TYPE}（不是账号事件）或 {@code VALIDATION_ERROR}
     */
    public static DomainEvent decode(EventEnvelope envelope) {
        EventType type = EventType.fromCode(envelope.type());
        if (type.isGameEvent()) {
            throw new GameException(
                    DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "信封 " + envelope.type() + " 不属于账号事件流");
        }
        EventMeta meta = metaOf(envelope);
        Map<String, Object> payload = envelope.payload();
        UserId userId = UserId.of(requiredText(payload, "userId"));

        return switch (type) {
            case USER_REGISTERED -> new UserRegisteredEvent(
                    meta, userId, requiredText(payload, "username"));
            case USER_LOGGED_IN -> new UserLoggedInEvent(
                    meta, userId, requiredText(payload, "sessionId"), integer(payload, "at"));
            case USER_LOGGED_OUT -> new UserLoggedOutEvent(
                    meta, userId, requiredText(payload, "sessionId"), integer(payload, "at"));
            // 房间事件属于 room:{id}:events，不在这里解码。
            default -> throw new GameException(
                    DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,
                    "信封 " + envelope.type() + " 不是账号事件（房间事件属于房间流）");
        };
    }

    /** 账号流没有对局阶段，固定用 GAME_INIT 与 round=1（与房间事件同一约定）。 */
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

    private static String requiredText(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw GameException.Factory.validation("账号事件缺少字段 " + field);
    }

    private static long integer(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw GameException.Factory.validation("字段 " + field + " 期望整数");
    }
}
