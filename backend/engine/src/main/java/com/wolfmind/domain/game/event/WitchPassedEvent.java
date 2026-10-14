package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code WITCH_PASSED}：本夜不用药（event-design.md §7.2，{@code PRIVATE}）。
 *
 * <p>主动跳过与超时共用本事件；{@code reason} 区分二者，便于分析"AI 是否经常卡到超时"。
 */
public record WitchPassedEvent(
        EventMeta meta,
        PlayerId witchId,
        SeatNo witchSeatNo,
        SkipReason reason) implements DomainEvent {

    public WitchPassedEvent {
        if (witchId == null) {
            throw GameException.Factory.validation("WitchPassedEvent 缺少 witchId");
        }
    }

    @Override
    public EventType type() {
        return EventType.WITCH_PASSED;
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
        payload.put("reason", reason == null ? null : reason.name());
        return EventPayloads.seal(payload);
    }
}
