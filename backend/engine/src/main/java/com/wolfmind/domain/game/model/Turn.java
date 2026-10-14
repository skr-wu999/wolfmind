package com.wolfmind.domain.game.model;

/**
 * 回合窗口：一次"轮到某个玩家表态"的窗口，由领域发放（domain-model.md §5.4）。
 *
 * <p>{@code deadline} 写入 {@code TURN_GRANTED} 事件，使超时判定可重放。
 */
public record Turn(PlayerId playerId, SeatNo seatNo, GamePhase phase, int turnIndex, long deadline) {

    public Turn {
        if (playerId == null || seatNo == null || phase == null) {
            throw new IllegalArgumentException("Turn 的 playerId / seatNo / phase 不能为空");
        }
    }

    public boolean expiredAt(long now) {
        return now >= deadline;
    }
}
