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
 * {@code USER_REGISTERED}：账号注册（event-design.md §7.3，{@code JUDGE}）。
 *
 * <p>可见性是 {@code JUDGE}：账号事件只用于审计，不面向任何浏览器推送
 * （websocket-protocol.md §7.2 明确列出"全部 {@code USER_*} 不推送"）。
 *
 * <p>payload 只有 {@code userId, username}——**不含口令、不含盐**。
 */
public record UserRegisteredEvent(
        EventMeta meta,
        UserId userId,
        String username) implements DomainEvent {

    public UserRegisteredEvent {
        if (userId == null) {
            throw GameException.Factory.validation("UserRegisteredEvent 缺少 userId");
        }
        if (username == null || username.isBlank()) {
            throw GameException.Factory.validation("UserRegisteredEvent 缺少 username");
        }
    }

    @Override
    public EventType type() {
        return EventType.USER_REGISTERED;
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
        payload.put("username", username);
        return EventPayloads.seal(payload);
    }
}