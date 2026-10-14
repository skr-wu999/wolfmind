package com.wolfmind.domain.user.event;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventPayloads;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.domain.user.model.UserId;

import java.util.Map;

/** {@code USER_LOGGED_OUT}：登出（event-design.md §7.3，{@code JUDGE}）。 */
public record UserLoggedOutEvent(
        EventMeta meta,
        UserId userId,
        String sessionId,
        long at) implements DomainEvent {

    public UserLoggedOutEvent {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            throw GameException.Factory.validation("UserLoggedOutEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.USER_LOGGED_OUT;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.JUDGE;
    }

    @Override
    public PlayerId targetPlayerId() {
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("userId", userId.value());
        payload.put("sessionId", sessionId);
        payload.put("at", at);
        return EventPayloads.seal(payload);
    }
}