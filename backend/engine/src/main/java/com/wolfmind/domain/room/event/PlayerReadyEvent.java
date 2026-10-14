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
 * {@code PLAYER_READY}：玩家准备（event-design.md §7.1，{@code PUBLIC}）。
 *
 * <p>按 api-spec.md §4，准备**只有置位语义，没有取消**（`POST /ready` 没有对应的 DELETE）。
 * 这不是遗漏：取消准备与"离开重进"等价，单独做一条取消语义会让就绪状态机多一个方向。
 */
public record PlayerReadyEvent(
        EventMeta meta,
        RoomId roomId,
        PlayerId playerId) implements DomainEvent {

    public PlayerReadyEvent {
        if (roomId == null || playerId == null) {
            throw GameException.Factory.validation("PlayerReadyEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_READY;
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
        return EventPayloads.seal(payload);
    }
}