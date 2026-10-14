package com.wolfmind.domain.room.event;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventPayloads;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.domain.room.model.RoomId;

import java.util.Map;

/**
 * {@code PLAYER_SEATED}：玩家或 AI 占据一个座位（event-design.md §7.1，{@code PUBLIC}）。
 *
 * <p>它同时表达"真人入座"与"AI 补位"：两者在规则上没有区别，差别只在 {@code controllerType}。
 * 拆成两个事件会让"座位是否已占"这个不变量有两处更新点。
 */
public record PlayerSeatedEvent(
        EventMeta meta,
        RoomId roomId,
        PlayerId playerId,
        int seatNo,
        ControllerType controllerType,
        String refId) implements DomainEvent {

    public PlayerSeatedEvent {
        if (roomId == null || playerId == null || controllerType == null) {
            throw GameException.Factory.validation("PlayerSeatedEvent 缺少必要字段");
        }
        if (seatNo < 1 || seatNo > 9) {
            throw GameException.Factory.validation("座位号必须在 1..9，实际为 " + seatNo);
        }
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_SEATED;
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
        payload.put("controllerType", controllerType.name());
        payload.put("refId", refId);
        return EventPayloads.seal(payload);
    }
}