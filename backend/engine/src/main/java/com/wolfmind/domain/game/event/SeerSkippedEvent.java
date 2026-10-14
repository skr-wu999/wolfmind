package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code SEER_SKIPPED}：预言家放弃或超时未查验（event-design.md §7.2，{@code PRIVATE}）。
 */
public record SeerSkippedEvent(
        EventMeta meta,
        PlayerId seerId,
        SeatNo seerSeatNo,
        SkipReason reason) implements DomainEvent {

    public SeerSkippedEvent {
        if (seerId == null || reason == null) {
            throw GameException.Factory.validation("SeerSkippedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.SEER_SKIPPED;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.PRIVATE;
    }

    @Override
    public PlayerId targetPlayerId() {
        return seerId;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("seerId", seerId.value());
        payload.put("seatNo", seerSeatNo == null ? null : seerSeatNo.value());
        payload.put("reason", reason.name());
        return EventPayloads.seal(payload);
    }
}
