package com.wolfmind.domain.game.model;

/** 对局内玩家标识：对局内稳定，与"谁在操作"解耦（ddd-design.md §1.3）。 */
public record PlayerId(String value) {

    public PlayerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
    }

    public static PlayerId of(String value) {
        return new PlayerId(value);
    }

    /** 由座位号派生的稳定标识，与 api-spec.md 示例中的 {@code p_1} 形式一致。 */
    public static PlayerId forSeat(SeatNo seatNo) {
        return new PlayerId("p_" + seatNo.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
