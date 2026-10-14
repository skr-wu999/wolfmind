package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code TURN_GRANTED}：发放回合窗口（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>本事件发放顺序行动窗口；夜间与投票由已提交阶段事件开放资格。
 * AI 无法自行开始回合或改变行动顺序；窗口倒计时使用服务端 {@code deadline}。
 */
public record TurnGrantedEvent(
        EventMeta meta,
        PlayerId playerId,
        SeatNo seatNo,
        com.wolfmind.domain.game.model.GamePhase turnPhase,
        int turnIndex,
        long deadline,
        ControllerType controllerType) implements DomainEvent {

    public TurnGrantedEvent {
        if (playerId == null || seatNo == null || turnPhase == null || controllerType == null) {
            throw GameException.Factory.validation("TurnGrantedEvent 缺少必要字段");
        }
    }

    @Override
    public EventType type() {
        return EventType.TURN_GRANTED;
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
        payload.put("playerId", playerId.value());
        payload.put("seatNo", seatNo.value());
        payload.put("phase", turnPhase.name());
        payload.put("turnIndex", turnIndex);
        payload.put("deadline", deadline);
        payload.put("controllerType", controllerType.name());
        return EventPayloads.seal(payload);
    }
}
