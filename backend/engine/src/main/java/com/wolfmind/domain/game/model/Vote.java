package com.wolfmind.domain.game.model;

/**
 * 一票（domain-model.md §5.4）。
 *
 * <p>一人一票，提交后不可改（改票需新事件，第一版不支持）。约束：{@code abstain = true} 时
 * {@code targetSeat} 必须为 {@code null}；{@code abstain = false} 时必须给出目标。
 */
public record Vote(int voterSeat, Integer targetSeat, boolean abstain) {

    public Vote {
        if (voterSeat < 1 || voterSeat > SeatNo.MAX) {
            throw new IllegalArgumentException("voterSeat 越界：" + voterSeat);
        }
        if (abstain && targetSeat != null) {
            throw new IllegalArgumentException("弃权票不得携带目标座位");
        }
        if (!abstain && targetSeat == null) {
            throw new IllegalArgumentException("非弃权票必须携带目标座位");
        }
        if (targetSeat != null && (targetSeat < 1 || targetSeat > SeatNo.MAX)) {
            throw new IllegalArgumentException("targetSeat 越界：" + targetSeat);
        }
    }

    public static Vote of(int voterSeat, int targetSeat) {
        return new Vote(voterSeat, targetSeat, false);
    }

    public static Vote abstain(int voterSeat) {
        return new Vote(voterSeat, null, true);
    }

    /** 规范化构造：{@code targetSeat == null} 即弃权，避免调用方写两份分支。 */
    public static Vote resolve(int voterSeat, Integer targetSeat) {
        return targetSeat == null
                ? abstain(voterSeat)
                : of(voterSeat, targetSeat.intValue());
    }
}
