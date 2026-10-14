package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code WITCH_SAVED}：女巫使用解药（event-design.md §7.2，{@code PRIVATE}）。
 *
 * <p>{@code PRIVATE} 而非 {@code WOLF}：解药是村民阵营的能力，狼人阵营不应该知道
 * "今夜刀口被救了"。这一条直接决定刀口是否落空，属于最敏感的信息。
 */
public record WitchSavedEvent(
        EventMeta meta,
        PlayerId witchId,
        SeatNo witchSeatNo,
        PlayerId targetId,
        SeatNo targetSeatNo) implements DomainEvent {

    public WitchSavedEvent {
        if (witchId == null || targetId == null) {
            throw GameException.Factory.validation("WitchSavedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.WITCH_SAVED;
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
        return witchId;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("witchId", witchId.value());
        payload.put("witchSeatNo", witchSeatNo == null ? null : witchSeatNo.value());
        payload.put("targetId", targetId.value());
        payload.put("targetSeatNo", targetSeatNo == null ? null : targetSeatNo.value());
        return EventPayloads.seal(payload);
    }
}
