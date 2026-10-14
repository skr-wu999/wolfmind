package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.Map;

/**
 * {@code WOLF_CHAT_SENT}：狼人夜聊的一条消息（event-design.md §7.2，{@code WOLF}）。
 *
 * <p>只改变每晚条数计数，不改变阶段、提名或回合；应用层据此把"之后只有夜聊"的
 * {@code expectedVersion} 视为未过期（api-spec.md §5.3）。
 */
public record WolfChatSentEvent(
        EventMeta meta,
        PlayerId wolfId,
        SeatNo wolfSeatNo,
        String chatId,
        String content) implements DomainEvent {

    public WolfChatSentEvent {
        if (wolfId == null || wolfSeatNo == null || chatId == null || content == null) {
            throw GameException.Factory.validation("WolfChatSentEvent 缺少必要字段");
        }
    }

    /** 消息标识：{@code {gameId}:{本事件的 aggregateVersion}}，与 speechId 同一派生方式。 */
    public static String chatIdFor(String gameId, int eventVersion) {
        return gameId + ":" + eventVersion;
    }

    @Override
    public EventType type() {
        return EventType.WOLF_CHAT_SENT;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.WOLF;
    }

    @Override
    public PlayerId targetPlayerId() {
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("wolfId", wolfId.value());
        payload.put("wolfSeatNo", wolfSeatNo.value());
        payload.put("chatId", chatId);
        payload.put("content", content);
        return EventPayloads.seal(payload);
    }
}
