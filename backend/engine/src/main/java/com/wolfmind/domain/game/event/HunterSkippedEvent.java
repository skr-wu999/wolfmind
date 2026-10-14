package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code HUNTER_SKIPPED}：猎人放弃开枪或超时（event-design.md §7.2，{@code PUBLIC}）。
 */
public record HunterSkippedEvent(
        EventMeta meta,
        PlayerId hunterId,
        SeatNo hunterSeatNo,
        SkipReason reason) implements DomainEvent {

    public HunterSkippedEvent {
        if (hunterId == null || reason == null) {
            throw GameException.Factory.validation("HunterSkippedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.HUNTER_SKIPPED;
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
        payload.put("hunterId", hunterId.value());
        payload.put("hunterSeatNo", hunterSeatNo == null ? null : hunterSeatNo.value());
        payload.put("reason", reason.name());
        return EventPayloads.seal(payload);
    }
}
