package com.wolfmind.domain.room.event;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventPayloads;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.domain.room.model.RoomId;

import java.util.Map;

/** {@code PLAYER_LEFT}：玩家离开座位（event-design.md §7.1，{@code PUBLIC}）。 */
public record PlayerLeftEvent(
        EventMeta meta,
        RoomId roomId,
        PlayerId playerId,
        int seatNo) implements DomainEvent {

    public PlayerLeftEvent {
        if (roomId == null || playerId == null) {
            throw GameException.Factory.validation("PlayerLeftEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_LEFT;
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
        payload.put("roomId", roomId.value());
        payload.put("playerId", playerId.value());
        payload.put("seatNo", seatNo);
        return EventPayloads.seal(payload);
    }
}