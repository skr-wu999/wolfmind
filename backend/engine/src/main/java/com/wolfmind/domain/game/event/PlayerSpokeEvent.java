package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.Visibility;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code PLAYER_SPOKE}：发言，含遗言（event-design.md §7.2，{@code PUBLIC}）。
 *
 * <p>流式 AI 发言的分片是**展示更新**而不是领域事件：最终被接受的发言只在这里提交一次
 * （AGENTS.md §9）。{@code turnIndex} 与 {@code phase} 让回放能还原发言顺序。
 */
public record PlayerSpokeEvent(
        EventMeta meta,
        PlayerId playerId,
        SeatNo seatNo,
        String speechId,
        String content,
        int turnIndex,
        GamePhase speechPhase) implements DomainEvent {

    public PlayerSpokeEvent {
        if (playerId == null || seatNo == null || content == null || speechPhase == null) {
            throw GameException.Factory.validation("PlayerSpokeEvent 缺少必要字段");
        }
    }

    /**
     * 发言标识：{@code {gameId}:{本事件的 aggregateVersion}}。确定性派生而不是随机生成，
     * 因此流式预览在提交前就能算出与最终 {@code PLAYER_SPOKE} 相同的标识。
     */
    public static String speechIdFor(String gameId, int eventVersion) {
        return gameId + ":" + eventVersion;
    }

    @Override
    public EventType type() {
        return EventType.PLAYER_SPOKE;
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
        payload.put("speechId", speechId);
        payload.put("content", content);
        payload.put("turnIndex", turnIndex);
        payload.put("phase", speechPhase.name());
        return EventPayloads.seal(payload);
    }
}
