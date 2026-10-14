package com.wolfmind.domain.room.model;

import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.SeatNo;

/**
 * 房间里的一个座位。
 *
 * <p>{@code controller} 为空表示空座。AI 座位**入座即视为就绪**
 * （redis-schema.md：`room:{id}:ready` 的 Set 在 `PLAYER_SEATED` 时一并加入），
 * 因此 {@code ready} 对 AI 座位恒为 true，不需要额外事件。
 */
public record RoomSeat(
        SeatNo seatNo,
        PlayerId playerId,
        PlayerController controller,
        boolean ready,
        long joinedAt) {

    /** 空座。 */
    public static RoomSeat empty(SeatNo seatNo) {
        return new RoomSeat(seatNo, null, null, false, 0L);
    }

    public boolean occupied() {
        return playerId != null;
    }

    public boolean isAi() {
        return controller != null && controller.type() == com.wolfmind.domain.game.model.ControllerType.AI;
    }

    /** 该座位是否满足"可开局"的就绪条件：AI 自动就绪，真人需显式准备。 */
    public boolean satisfied() {
        return occupied() && ready;
    }
}