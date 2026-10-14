package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.TallyResult;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code VOTE_TALLY_RESOLVED}：计票完成（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>{@code result} 取 {@code EXILE}（有人出局）、{@code TIE}（平票，无人出局）、
 * {@code ALL_ABSTAIN}（全员弃权）。
 */
public record VoteTallyResolvedEvent(
        EventMeta meta,
        TallyResult.Result result,
        List<TallyEntry> tally,
        int maxVotes,
        PlayerId exiledPlayerId,
        List<Integer> tieSeats) implements DomainEvent {

    public VoteTallyResolvedEvent {
        if (result == null) {
            throw GameException.Factory.validation("VoteTallyResolvedEvent 缺少 result");
        }
        tally = List.copyOf(tally);
        tieSeats = List.copyOf(tieSeats);
    }

    /** 单个座位的得票明细。 */
    public record TallyEntry(int seatNo, int votes) {}

    @Override
    public EventType type() {
        return EventType.VOTE_TALLY_RESOLVED;
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
        List<Map<String, Object>> entries = tally.stream()
                .map(entry -> {
                    Map<String, Object> item = EventPayloads.create();
                    item.put("seatNo", entry.seatNo());
                    item.put("votes", entry.votes());
                    return EventPayloads.seal(item);
                })
                .toList();
        payload.put("tally", entries);
        payload.put("maxVotes", maxVotes);
        payload.put("result", result.name());
        if (exiledPlayerId != null) {
            payload.put("exiledPlayerId", exiledPlayerId.value());
        }
        if (!tieSeats.isEmpty()) {
            payload.put("tieSeats", tieSeats);
        }
        return EventPayloads.seal(payload);
    }
}
