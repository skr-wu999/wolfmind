package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code HUNTER_SHOT}：猎人开枪（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>开枪本身是公开事实（全场看到猎人亮身份并带走一人），因此可见性是 {@code PUBLIC}；
 * 开枪后立即判定胜负，可能紧跟 {@code GAME_FINISHED}。
 */
public record HunterShotEvent(
        EventMeta meta,
        PlayerId hunterId,
        SeatNo hunterSeatNo,
        PlayerId targetId,
        SeatNo targetSeatNo) implements DomainEvent {

    public HunterShotEvent {
        if (hunterId == null || targetId == null) {
            throw GameException.Factory.validation("HunterShotEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.HUNTER_SHOT;
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
        payload.put("hunterId", hunterId.value());
        payload.put("hunterSeatNo", hunterSeatNo == null ? null : hunterSeatNo.value());
        payload.put("targetId", targetId.value());
        payload.put("targetSeatNo", targetSeatNo == null ? null : targetSeatNo.value());
        return EventPayloads.seal(payload);
    }
}
