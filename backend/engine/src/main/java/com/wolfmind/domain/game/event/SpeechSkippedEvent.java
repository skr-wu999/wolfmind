package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code SPEECH_SKIPPED}：发言超时或主动跳过（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>契约目录里没有独立的"回合跳过"事件，因此超时发言就产生本事件 + 下一位的
 * {@code TURN_GRANTED}；前端把它渲染成一条"已跳过"记录，而不是错误。
 */
public record SpeechSkippedEvent(
        EventMeta meta,
        PlayerId playerId,
        SeatNo seatNo,
        SkipReason reason,
        int turnIndex) implements DomainEvent {

    public SpeechSkippedEvent {
        if (playerId == null || seatNo == null || reason == null) {
            throw GameException.Factory.validation("SpeechSkippedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.SPEECH_SKIPPED;
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
        payload.put("playerId", playerId.value());
        payload.put("seatNo", seatNo.value());
        payload.put("reason", reason.name());
        payload.put("turnIndex", turnIndex);
        return EventPayloads.seal(payload);
    }
}
