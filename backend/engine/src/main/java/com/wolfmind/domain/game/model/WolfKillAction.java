package com.wolfmind.domain.game.model;

/**
 * 狼人提名击杀目标（domain-model.md §5.3）。
 *
 * <p>{@code target == null} 表示该狼人本夜弃刀；存活狼人全部弃刀时形成空刀
 * （{@code WOLF_KILL_SELECTED.targetId = null}），仍是可回放的对局事实。
 */
public record WolfKillAction(SeatNo actor, SeatNo target) implements Action {

    public WolfKillAction {
        if (actor == null) {
            throw GameException.Factory.validation("WolfKillAction 需要 actor");
        }
    }

    public static WolfKillAction at(int actorSeat, int targetSeat) {
        return new WolfKillAction(SeatNo.of(actorSeat), SeatNo.of(targetSeat));
    }

    /** 空刀：不指定目标。 */
    public static WolfKillAction empty(int actorSeat) {
        return new WolfKillAction(SeatNo.of(actorSeat), null);
    }

    public static WolfKillAction resolve(int actorSeat, Integer targetSeat) {
        return targetSeat == null ? empty(actorSeat) : at(actorSeat, targetSeat.intValue());
    }

    public boolean isEmptyKill() {
        return target == null;
    }

    @Override
    public ActionType type() {
        return ActionType.WOLF_KILL;
    }
}
