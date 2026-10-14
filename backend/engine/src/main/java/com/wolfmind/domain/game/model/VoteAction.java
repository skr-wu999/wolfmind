package com.wolfmind.domain.game.model;

/**
 * 投票：{@code target == null} 且 {@code abstain = true} 表示弃权（domain-model.md §5.3）。
 *
 * <p>非法组合（弃权但带目标、投票但无目标）在构造期即被拒绝，不进入领域校验阶段。
 */
public record VoteAction(SeatNo actor, SeatNo target, boolean abstain) implements Action {

    public VoteAction {
        if (actor == null) {
            throw GameException.Factory.validation("VoteAction 需要 actor");
        }
        if (abstain && target != null) {
            throw GameException.Factory.validation("弃权票不得携带目标");
        }
        if (!abstain && target == null) {
            throw GameException.Factory.validation("非弃权票必须携带目标");
        }
    }

    public static VoteAction to(int actorSeat, int targetSeat) {
        return new VoteAction(SeatNo.of(actorSeat), SeatNo.of(targetSeat), false);
    }

    public static VoteAction abstain(int actorSeat) {
        return new VoteAction(SeatNo.of(actorSeat), null, true);
    }

    /** 规范化构造：{@code targetSeat == null} 即弃权。 */
    public static VoteAction resolve(int actorSeat, Integer targetSeat) {
        return targetSeat == null ? abstain(actorSeat) : to(actorSeat, targetSeat.intValue());
    }

    @Override
    public ActionType type() {
        return ActionType.VOTE;
    }
}
