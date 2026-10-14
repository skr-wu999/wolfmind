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
 * {@code PLAYER_CONTROLLER_CHANGED}：座位控制者变更（房间流形态，event-design.md §7.1，{@code PUBLIC}）。
 *
 * <p><b>这个类型同时出现在房间流与对局流。</b>两条流用同一组字段名，差别只有一处：
 * 对局流形态额外带 {@code playerId}（因为对局里同一个座位可能换人，需要指明是谁），
 * 房间流形态只到座位粒度。TASKS.md 的实现期裁决 7 已经声明过这一点。
 *
 * <p>{@code toType} 为空表示座位回到空置（删除 AI 座位）。
 */
public record RoomControllerChangedEvent(
        EventMeta meta,
        RoomId roomId,
        int seatNo,
        ControllerType fromType,
        String fromRefId,
        ControllerType toType,
        String toRefId) implements DomainEvent {

    public RoomControllerChangedEvent {
        if (roomId == null) {
            throw GameException.Factory.validation("RoomControllerChangedEvent 缺少 roomId");
        }
        if (seatNo < 1 || seatNo > 9) {
            throw GameException.Factory.validation("座位号必须在 1..9，实际为 " + seatNo);
        }
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_CONTROLLER_CHANGED;
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
        payload.put("seatNo", seatNo);
        payload.put("fromType", fromType == null ? null : fromType.name());
        payload.put("fromRefId", fromRefId);
        payload.put("toType", toType == null ? null : toType.name());
        // 契约里这个键名是 refId（不是 toRefId）；见 event-design.md §7.1 的字段表。
        payload.put("refId", toRefId);
        return EventPayloads.seal(payload);
    }
}