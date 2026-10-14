package com.wolfmind.domain.game.model;

import java.util.Comparator;
import java.util.List;

/**
 * 计票判定结果（domain-model.md §6.2 的 {@code VoteTallyPolicy} 输出）。
 *
 * @param result       判定类型，取值 {@link Result}
 * @param tally        每个被投票座位的得票数，按座位号升序
 * @param maxVotes     最高得票数；全弃权时为 0
 * @param exiledSeat   出局座位；{@code TIE} 与 {@code ALL_ABSTAIN} 时为 {@code null}
 * @param tieSeats     并列最高票的座位号，按升序；非平票时为空
 * @param abstainCount 弃权票数
 */
public record TallyResult(
        Result result,
        List<SeatTally> tally,
        int maxVotes,
        Integer exiledSeat,
        List<Integer> tieSeats,
        int abstainCount) {

    /** 计票结果类型（event-design.md §7.2）。 */
    public enum Result {
        /** 有人出局。 */
        EXILE,
        /** 平票，无人出局。 */
        TIE,
        /** 全员弃权。 */
        ALL_ABSTAIN
    }

    public TallyResult {
        if (result == null) {
            throw new IllegalArgumentException("计票结果类型不能为空");
        }
        tally = List.copyOf(tally);
        tieSeats = tieSeats.stream().sorted(Comparator.naturalOrder()).toList();
    }

    /** 有人出局。 */
    public static TallyResult exile(
            List<SeatTally> tally, int maxVotes, int exiledSeat, int abstainCount) {
        return new TallyResult(Result.EXILE, tally, maxVotes, exiledSeat, List.of(), abstainCount);
    }

    /** 平票，无人出局。 */
    public static TallyResult tie(
            List<SeatTally> tally, int maxVotes, List<Integer> tieSeats, int abstainCount) {
        return new TallyResult(Result.TIE, tally, maxVotes, null, tieSeats, abstainCount);
    }

    /** 全员弃权。 */
    public static TallyResult allAbstain(int abstainCount) {
        return new TallyResult(Result.ALL_ABSTAIN, List.of(), 0, null, List.of(), abstainCount);
    }

    public boolean exiled() {
        return result == Result.EXILE && exiledSeat != null;
    }

    /** 单个座位得票。 */
    public record SeatTally(int seatNo, int votes) {
        public SeatTally {
            if (votes < 0) {
                throw new IllegalArgumentException("得票数不能为负");
            }
        }
    }
}
