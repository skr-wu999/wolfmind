package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GAME_FINISHED}：分出胜负（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>{@code mvpCandidates} 属于可选字段（event-design.md §6：新增可选字段不递增
 * {@code schemaVersion}），第一版不产出，保留在契约里以便后续填充。
 */
public record GameFinishedEvent(
        EventMeta meta,
        Camp winner,
        String reason,
        int rounds,
        List<SeatNo> survivors,
        List<PlayerId> mvpCandidates) implements DomainEvent {

    /** 胜负原因：狼全灭。 */
    public static final String REASON_ALL_WOLVES_ELIMINATED = "ALL_WOLVES_ELIMINATED";
    /** 胜负原因：狼数 ≥ 村民阵营存活数。 */
    public static final String REASON_WOLVES_OUTNUMBER_VILLAGE = "WOLVES_OUTNUMBER_VILLAGE";
    /** 胜负原因：屠边，平民全部出局。 */
    public static final String REASON_ALL_VILLAGERS_ELIMINATED = "ALL_VILLAGERS_ELIMINATED";
    /** 胜负原因：屠边，神职全部出局。 */
    public static final String REASON_ALL_GODS_ELIMINATED = "ALL_GODS_ELIMINATED";

    public GameFinishedEvent {
        if (winner == null || reason == null) {
            throw GameException.Factory.validation("GameFinishedEvent 缺少 winner / reason");
        }
        survivors = List.copyOf(survivors);
        mvpCandidates = List.copyOf(mvpCandidates);
    }

    @Override
    public EventType type() {
        return EventType.GAME_FINISHED;
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
        payload.put("winner", winner.name());
        payload.put("reason", reason);
        payload.put("rounds", rounds);
        payload.put("survivors", survivors.stream().map(SeatNo::value).toList());
        if (!mvpCandidates.isEmpty()) {
            payload.put("mvpCandidates", mvpCandidates.stream().map(PlayerId::value).toList());
        }
        return EventPayloads.seal(payload);
    }
}
