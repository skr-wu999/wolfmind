package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code WITCH_POISONED}：女巫使用毒药（event-design.md §7.2，{@code PRIVATE}）。
 *
 * <p>被毒者的死讯在天亮时作为 {@code DEATH_ANNOUNCED} 公布，但"为什么死"不在公开事件里：
 * 死因只在 {@code JUDGE} 的 {@code PLAYER_KILLED} 中（event-design.md §4）。
 */
public record WitchPoisonedEvent(
        EventMeta meta,
        PlayerId witchId,
        SeatNo witchSeatNo,
        PlayerId targetId,
        SeatNo targetSeatNo) implements DomainEvent {

    public WitchPoisonedEvent {
        if (witchId == null || targetId == null) {
            throw GameException.Factory.validation("WitchPoisonedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.WITCH_POISONED;
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
