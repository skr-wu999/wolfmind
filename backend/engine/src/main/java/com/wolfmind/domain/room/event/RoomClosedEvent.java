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

/** {@code ROOM_CLOSED}：房间关闭（event-design.md §7.1，{@code PUBLIC}）。 */
public record RoomClosedEvent(
        EventMeta meta,
        RoomId roomId,
        String reason) implements DomainEvent {

    public RoomClosedEvent {
        if (roomId == null) {
            throw GameException.Factory.validation("RoomClosedEvent 缺少 roomId");
        }
    }

    @Override
    public EventType type() {
        return EventType.ROOM_CLOSED;
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
        payload.put("reason", reason);
        return EventPayloads.seal(payload);
    }
}