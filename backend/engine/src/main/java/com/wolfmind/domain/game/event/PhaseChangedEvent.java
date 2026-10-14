package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code PHASE_CHANGED}：阶段跳转（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>转换必须落在 ddd-design.md §3.2 的转换表内；{@code resumePhase} 只在插入阶段
 * （{@code HUNTER_SHOOT}）出现，记录开枪结束后要回到的阶段。
 *
 * <p>{@code deadline} 是本阶段的整体时限（{@code DAY_VOTE}、{@code NIGHT_WOLF} 等无回合窗口的
 * 阶段用它做超时判定）；有回合窗口的阶段由 {@code TURN_GRANTED.deadline} 承担。
 */
public record PhaseChangedEvent(
        EventMeta meta,
        GamePhase from,
        GamePhase to,
        int turnSeq,
        GamePhase resumePhase,
        Long deadline) implements DomainEvent {

    public PhaseChangedEvent {
        if (from == null || to == null) {
            throw GameException.Factory.validation("PhaseChangedEvent 缺少 from / to");
        }
    }

    public static PhaseChangedEvent of(EventMeta meta, GamePhase from, GamePhase to, int turnSeq) {
        return new PhaseChangedEvent(meta, from, to, turnSeq, null, null);
    }

    @Override
    public EventType type() {
        return EventType.PHASE_CHANGED;
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
        payload.put("from", from.name());
        payload.put("to", to.name());
        payload.put("round", round());
        payload.put("turnSeq", turnSeq);
        if (resumePhase != null) {
            payload.put("resumePhase", resumePhase.name());
        }
        if (deadline != null) {
            payload.put("deadline", deadline);
        }
        return EventPayloads.seal(payload);
    }
}
