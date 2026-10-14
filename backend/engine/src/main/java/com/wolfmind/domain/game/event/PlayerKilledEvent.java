package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.DeathCause;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code PLAYER_KILLED}：死亡事实（event-design.md §7.2，{@code JUDGE}）。
 *
 * <p>死亡在夜间结算时就已经是领域事实，但"天亮公布"是另一件事（{@code DEATH_ANNOUNCED}，
 * {@code PUBLIC}）。这样"昨夜谁死了"与"为什么死"在事件层就是两件事，天然防泄漏。
 *
 * <p>{@code causes} 是死因集合，技能可用性判定必须读集合：被毒死的猎人不能开枪（INV-8）。
 * {@code primaryCause} 仅用于展示。
 */
public record PlayerKilledEvent(
        EventMeta meta,
        PlayerId playerId,
        SeatNo seatNo,
        List<DeathCause> causes,
        DeathCause primaryCause) implements DomainEvent {

    public PlayerKilledEvent {
        if (playerId == null || seatNo == null) {
            throw GameException.Factory.validation("PlayerKilledEvent 缺少 playerId / seatNo");
        }
        if (causes == null || causes.isEmpty()) {
            throw GameException.Factory.validation("PlayerKilledEvent.causes 不能为空");
        }
        causes = List.copyOf(causes);
        if (primaryCause == null) {
            primaryCause = DeathCause.primaryOf(causes);
        }
    }

    public boolean causedBy(DeathCause cause) {
        return causes.contains(cause);
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_KILLED;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Visibility visibility() {
        return Visibility.JUDGE;
    }

    @Override
    public PlayerId targetPlayerId() {
        return playerId;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("playerId", playerId.value());
        payload.put("seatNo", seatNo.value());
        payload.put("causes", causes.stream().map(Enum::name).toList());
        payload.put("primaryCause", primaryCause.name());
        payload.put("round", round());
        return EventPayloads.seal(payload);
    }
}
