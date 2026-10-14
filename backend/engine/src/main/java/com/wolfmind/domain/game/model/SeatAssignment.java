package com.wolfmind.domain.game.model;

/**
 * 开局时的座位分配输入：座位号 + 控制者 + 是否就绪。
 *
 * <p>来自 Room 聚合（INV-R2：只有全部座位就绪才能开局）。领域只做 INV-1 校验，不读 Room。
 */
public record SeatAssignment(SeatNo seatNo, PlayerController controller, boolean ready) {

    public SeatAssignment {
        if (seatNo == null || controller == null) {
            throw new IllegalArgumentException("SeatAssignment 的 seatNo 与 controller 不能为空");
        }
    }

    public static SeatAssignment of(int seatNo, PlayerController controller, boolean ready) {
        return new SeatAssignment(SeatNo.of(seatNo), controller, ready);
    }

    public static SeatAssignment human(int seatNo, String userId) {
        return new SeatAssignment(SeatNo.of(seatNo), PlayerController.human(userId), true);
    }

    public static SeatAssignment ai(int seatNo, String agentId) {
        return new SeatAssignment(SeatNo.of(seatNo), PlayerController.ai(agentId), true);
    }

    public static SeatAssignment bot(int seatNo, String strategyKey) {
        return new SeatAssignment(SeatNo.of(seatNo), PlayerController.bot(strategyKey), true);
    }
}
