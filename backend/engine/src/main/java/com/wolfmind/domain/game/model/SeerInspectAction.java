package com.wolfmind.domain.game.model;

/** 预言家查验（domain-model.md §5.3）：目标必填，结果由领域计算。 */
public record SeerInspectAction(SeatNo actor, SeatNo target) implements Action {

    public SeerInspectAction {
        if (actor == null || target == null) {
            throw GameException.Factory.validation("SeerInspectAction 需要 actor 与 target");
        }
    }

    public static SeerInspectAction of(int actorSeat, int targetSeat) {
        return new SeerInspectAction(SeatNo.of(actorSeat), SeatNo.of(targetSeat));
    }

    @Override
    public ActionType type() {
        return ActionType.SEER_INSPECT;
    }
}
