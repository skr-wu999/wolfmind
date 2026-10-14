package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code SEER_INSPECTED}：预言家查验（event-design.md §7.2，{@code PRIVATE}）。
 *
 * <p>{@code result} 是被查验者的阵营。只有预言家本人可见——给其他座位（包括 AI Agent）
 * 更多信息就等于作弊，也会让实验结果失去意义（event-design.md §4 硬规则 1）。
 */
public record SeerInspectedEvent(
        EventMeta meta,
        PlayerId seerId,
        SeatNo seerSeatNo,
        PlayerId targetId,
        SeatNo targetSeatNo,
        Camp result) implements DomainEvent {

    public SeerInspectedEvent {
        if (seerId == null || targetId == null || result == null) {
            throw GameException.Factory.validation("SeerInspectedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.SEER_INSPECTED;
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
        return seerId;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("seerId", seerId.value());
        payload.put("seerSeatNo", seerSeatNo.value());
        payload.put("targetId", targetId.value());
        payload.put("targetSeatNo", targetSeatNo == null ? null : targetSeatNo.value());
        payload.put("result", result.name());
        return EventPayloads.seal(payload);
    }
}
