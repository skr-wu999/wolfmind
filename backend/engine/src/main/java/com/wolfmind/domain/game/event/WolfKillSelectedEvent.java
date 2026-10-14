package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code WOLF_KILL_SELECTED}：狼刀定案（event-design.md §7.2，{@code WOLF}）。
 *
 * <p>{@code targetId} 为 {@code null} 表示空刀（存活狼人全部弃刀或全部超时），仍然产生事件，
 * 因为"当夜无人被刀"是可回放的对局事实。{@code nominations} 记录定案依据，便于复盘狼队内部意见。
 */
public record WolfKillSelectedEvent(
        EventMeta meta,
        PlayerId targetId,
        SeatNo targetSeatNo,
        List<Nomination> nominations) implements DomainEvent {

    public WolfKillSelectedEvent {
        nominations = List.copyOf(nominations);
    }

    /** 一条提名记录：谁提名了谁（{@code targetSeatNo} 为空表示该狼人弃刀）。 */
    public record Nomination(int wolfSeatNo, Integer targetSeatNo) {}

    public boolean isEmptyKill() {
        return targetId == null;
    }

    @Override
    public EventType type() {
        return EventType.WOLF_KILL_SELECTED;
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
        payload.put("targetId", targetId == null ? null : targetId.value());
        payload.put("targetSeatNo", targetSeatNo == null ? null : targetSeatNo.value());
        List<Map<String, Object>> nominationList = nominations.stream()
                .map(nomination -> {
                    Map<String, Object> item = EventPayloads.create();
                    item.put("wolfSeatNo", nomination.wolfSeatNo());
                    item.put("targetSeatNo", nomination.targetSeatNo());
                    return EventPayloads.seal(item);
                })
                .toList();
        payload.put("nominations", nominationList);
        return EventPayloads.seal(payload);
    }
}
