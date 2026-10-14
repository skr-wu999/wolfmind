package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code PLAYER_VOTED}：投票（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>弃权用 {@code abstain = true} 且 {@code targetId / targetSeatNo} 为 {@code null} 表达。
 */
public record PlayerVotedEvent(
        EventMeta meta,
        PlayerId playerId,
        SeatNo seatNo,
        PlayerId targetId,
        SeatNo targetSeatNo,
        boolean abstain) implements DomainEvent {

    public PlayerVotedEvent {
        if (playerId == null || seatNo == null) {
            throw GameException.Factory.validation("PlayerVotedEvent 缺少 voter");
        }
        if (abstain && (targetId != null || targetSeatNo != null)) {
            throw GameException.Factory.validation("弃权票不得携带目标");
        }
        if (!abstain && (targetId == null || targetSeatNo == null)) {
            throw GameException.Factory.validation("非弃权票必须携带目标");
        }
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_VOTED;
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
        payload.put("targetId", targetId == null ? null : targetId.value());
        payload.put("targetSeatNo", targetSeatNo == null ? null : targetSeatNo.value());
        payload.put("abstain", abstain);
        return EventPayloads.seal(payload);
    }
}
