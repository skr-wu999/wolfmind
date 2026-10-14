package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GAME_RECOVERED}：从快照恢复（event-design.md §7.2，{@code JUDGE}）。
 *
 * <p>恢复是对局历史里可追溯的事实，不是服务端的隐式修补：运维接口触发的恢复与崩溃恢复
 * 都产生本事件（system-design.md §3.6）。
 */
public record GameRecoveredEvent(
        EventMeta meta,
        int snapshotVersion,
        int replayedTo,
        String source) implements DomainEvent {

    public GameRecoveredEvent {
        if (source == null || source.isBlank()) {
            throw GameException.Factory.validation("GameRecoveredEvent.source 不能为空");
        }
    }

    @Override
    public EventType type() {
        return EventType.GAME_RECOVERED;
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
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> payload = EventPayloads.create();
        payload.put("snapshotVersion", snapshotVersion);
        payload.put("replayedTo", replayedTo);
        payload.put("source", source);
        return EventPayloads.seal(payload);
    }
}
