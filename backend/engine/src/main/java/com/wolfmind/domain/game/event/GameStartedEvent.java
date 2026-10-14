package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.Visibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GAME_STARTED}：开局（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>{@code rules} 与 {@code roleComposition} 必须写入事件：重放时使用事件里的规则，而不是
 * 服务端当前配置，否则实验室跑过的旧对局无法复现（domain-model.md §3）。
 *
 * <p>{@code seats} 里只记录"谁坐在哪个座位、由谁控制"，不含角色：角色由紧随其后的
 * {@code ROLE_ASSIGNED} 逐人私密下发（{@code PRIVATE}），这样开局事件本身不泄漏身份。
 */
public record GameStartedEvent(
        EventMeta meta,
        GameId gameId,
        String roomId,
        List<SeatSpec> seats,
        RoleComposition roleComposition,
        GameRules rules,
        List<Integer> turnOrder,
        int startedRound) implements DomainEvent {

    public GameStartedEvent {
        seats = List.copyOf(seats);
        turnOrder = List.copyOf(turnOrder);
    }

    /** 一个座位的开局分配。 */
    public record SeatSpec(int seatNo, String playerId, String controllerType, String refId) {

        public static SeatSpec of(SeatAssignment assignment) {
            return new SeatSpec(
                    assignment.seatNo().value(),
                    PlayerId.forSeat(assignment.seatNo()).value(),
                    assignment.controller().type().name(),
                    assignment.controller().refId());
        }

        public PlayerController controller() {
            return new PlayerController(ControllerType.valueOf(controllerType), refId);
        }
    }

    @Override
    public EventType type() {
        return EventType.GAME_STARTED;
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
        payload.put("gameId", gameId.value());
        payload.put("roomId", roomId);

        List<Map<String, Object>> seatList = new ArrayList<>();
        for (SeatSpec seat : seats) {
            Map<String, Object> entry = EventPayloads.create();
            entry.put("seatNo", seat.seatNo());
            entry.put("playerId", seat.playerId());
            entry.put("controllerType", seat.controllerType());
            entry.put("refId", seat.refId());
            seatList.add(entry);
        }
        payload.put("seats", List.copyOf(seatList));

        Map<String, Integer> composition = new TreeMap<>();
        for (Map.Entry<com.wolfmind.domain.game.model.Role, Integer> entry
                : roleComposition.counts().entrySet()) {
            composition.put(entry.getKey().name(), entry.getValue());
        }
        payload.put("roleComposition", Map.copyOf(composition));

        Map<String, Object> ruleMap = EventPayloads.create();
        ruleMap.put("speechMaxLength", rules.speechMaxLength());
        ruleMap.put("turnDeadlineMs", rules.turnDeadlineMs());
        ruleMap.put("agentTurnDeadlineMs", rules.agentTurnDeadlineMs());
        ruleMap.put("voteDeadlineMs", rules.voteDeadlineMs());
        ruleMap.put("firstNightLastWords", rules.firstNightLastWords());
        ruleMap.put("laterNightLastWords", rules.laterNightLastWords());
        ruleMap.put("exileLastWords", rules.exileLastWords());
        ruleMap.put("dayShotLastWords", rules.dayShotLastWords());
        ruleMap.put("stalledRoundsLimit", rules.stalledRoundsLimit());
        ruleMap.put("witchSelfSave", rules.witchSelfSave());
        ruleMap.put("witchSelfSaveFirstNightOnly", rules.witchSelfSaveFirstNightOnly());
        ruleMap.put("witchSaveTargetMustBeKilled", rules.witchSaveTargetMustBeKilled());
        ruleMap.put("allowWolfEmptyKill", rules.allowWolfEmptyKill());
        ruleMap.put("wolfKillTieBreak", rules.wolfKillTieBreak().name());
        ruleMap.put("voteTieRule", rules.voteTieRule().name());
        ruleMap.put("hunterCanShootWhenPoisoned", rules.hunterCanShootWhenPoisoned());
        ruleMap.put("winCondition", rules.winCondition().name());
        ruleMap.put("abstainAllowed", rules.abstainAllowed());
        payload.put("rules", Map.copyOf(ruleMap));

        payload.put("turnOrder", List.copyOf(turnOrder));
        payload.put("round", startedRound);
        return EventPayloads.seal(payload);
    }

    @Override
    public GamePhase phase() {
        return meta.phase();
    }

    @Override
    public int round() {
        return meta.round();
    }

    @Override
    public String actorId() {
        return meta.actorId();
    }

    @Override
    public String eventId() {
        return meta.eventId();
    }

    @Override
    public String causationId() {
        return meta.causationId();
    }

    @Override
    public String correlationId() {
        return meta.correlationId();
    }

    @Override
    public long occurredAt() {
        return meta.occurredAt();
    }

    @Override
    public int version() {
        return meta.version();
    }
}
