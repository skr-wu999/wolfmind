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

/**
 * {@code ROOM_CREATED}：房间创建（event-design.md §7.1，{@code PUBLIC}）。
 *
 * <p>{@code config} 是创建时的完整配置。开局时它会**被复制进 {@code GAME_STARTED.rules}**，
 * 因此开局后修改房间配置不影响进行中的对局（api-spec.md §4 尾注）——
 * 这也解释了为什么配置必须随本事件固化，而不是只留在房间可变状态里。
 */
public record RoomCreatedEvent(
        EventMeta meta,
        RoomId roomId,
        String name,
        String ownerId,
        Map<String, Object> config) implements DomainEvent {

    public RoomCreatedEvent {
        if (roomId == null || ownerId == null) {
            throw GameException.Factory.validation("RoomCreatedEvent 缺少必要字段");
        }
        config = config == null ? Map.of() : EventPayloads.seal(config);
    }

    @Override
    public EventType type() {
        return EventType.ROOM_CREATED;
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
        payload.put("name", name);
        payload.put("ownerId", ownerId);
        payload.put("config", config);
        return EventPayloads.seal(payload);
    }
}