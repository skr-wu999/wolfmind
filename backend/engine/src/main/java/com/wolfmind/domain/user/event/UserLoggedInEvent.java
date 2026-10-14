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

/**
 * {@code USER_LOGGED_IN}：登录（event-design.md §7.3，{@code JUDGE}）。
 *
 * <p>payload 含 {@code sessionId} 但**不含会话令牌**（redis-schema.md §5.9：
 * 令牌是可撤销的凭据，只存在于会话键里，不进长期归档）。{@code sessionId} 用于审计
 * "哪次会话登录了"，即使令牌已过期也能对上它们的生命周期。
 */
public record UserLoggedInEvent(
        EventMeta meta,
        UserId userId,
        String sessionId,
        long at) implements DomainEvent {

    public UserLoggedInEvent {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            throw GameException.Factory.validation("UserLoggedInEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.USER_LOGGED_IN;
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