package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GAME_ABORTED}：中止对局（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>用于"人数不足、不可恢复"等无法产生胜负的情形。中止是终态，之后不接受任何行动（INV-12）。
 */
public record GameAbortedEvent(
        EventMeta meta,
        String reason,
        int atVersion) implements DomainEvent {

    public GameAbortedEvent {
        if (reason == null || reason.isBlank()) {
            throw GameException.Factory.validation("GameAbortedEvent.reason 不能为空");
        }
    }

    @Override
    public EventType type() {
        return EventType.GAME_ABORTED;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.PUBLIC;
    }

    @Override
    public PlayerId targetPlayerId() {
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("reason", reason);
        payload.put("atVersion", atVersion);
        return EventPayloads.seal(payload);
    }
}
