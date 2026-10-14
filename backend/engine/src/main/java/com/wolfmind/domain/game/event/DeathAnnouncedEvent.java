package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code DEATH_ANNOUNCED}：公布死讯（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>{@code deaths} 为空数组时表示平安夜，**仍然产生事件**：前端据此显示"昨夜无人死亡"，
 * 而不是靠"没有收到事件"推断。
 *
 * <p>只带 {@code playerId} 与 {@code seatNo}，不带死因：被放逐与被枪击本身已经是公开事件，
 * 而狼刀与毒药的区别是隐藏信息。
 */
public record DeathAnnouncedEvent(
        EventMeta meta,
        List<DeathEntry> deaths) implements DomainEvent {

    public DeathAnnouncedEvent {
        deaths = List.copyOf(deaths);
    }

    /** 一条死讯。 */
    public record DeathEntry(PlayerId playerId, SeatNo seatNo) {}

    public boolean peacefulNight() {
        return deaths.isEmpty();
    }

    @Override
    public EventType type() {
        return EventType.DEATH_ANNOUNCED;
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
        payload.put("round", round());
        List<Map<String, Object>> entries = deaths.stream()
                .map(death -> {
                    Map<String, Object> item = EventPayloads.create();
                    item.put("playerId", death.playerId().value());
                    item.put("seatNo", death.seatNo().value());
                    return EventPayloads.seal(item);
                })
                .toList();
        payload.put("deaths", entries);
        return EventPayloads.seal(payload);
    }
}
