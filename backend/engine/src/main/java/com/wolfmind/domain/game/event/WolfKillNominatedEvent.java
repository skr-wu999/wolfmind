package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code WOLF_KILL_NOMINATED}：狼人提名目标（event-design.md §7.2，{@code WOLF}）。
 *
 * <p>{@code WOLF} 可见性意味着村民阵营（包括他们的 Agent）永远拿不到这条事件；
 * 过滤只有 {@code VisibilityFilter} 一份实现。
 */
public record WolfKillNominatedEvent(
        EventMeta meta,
        PlayerId wolfId,
        SeatNo wolfSeatNo,
        PlayerId targetId,
        SeatNo targetSeatNo,
        boolean emptyKill) implements DomainEvent {

    public WolfKillNominatedEvent {
        if (wolfId == null || wolfSeatNo == null) {
            throw GameException.Factory.validation("WolfKillNominatedEvent 缺少提名者");
        }
        if (emptyKill && targetId != null) {
            throw GameException.Factory.validation("空刀提名不得携带目标");
        }
        if (!emptyKill && targetId == null) {
            throw GameException.Factory.validation("非空刀提名必须携带目标");
        }
    }

    public static WolfKillNominatedEvent empty(EventMeta meta, PlayerId wolfId, SeatNo wolfSeatNo) {
        return new WolfKillNominatedEvent(meta, wolfId, wolfSeatNo, null, null, true);
    }

    @Override
    public EventType type() {
        return EventType.WOLF_KILL_NOMINATED;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.WOLF;
    }

    @Override
    public PlayerId targetPlayerId() {
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("wolfId", wolfId.value());
        payload.put("wolfSeatNo", wolfSeatNo.value());
        payload.put("targetId", targetId == null ? null : targetId.value());
        payload.put("targetSeatNo", targetSeatNo == null ? null : targetSeatNo.value());
        payload.put("emptyKill", emptyKill);
        return EventPayloads.seal(payload);
    }
}
