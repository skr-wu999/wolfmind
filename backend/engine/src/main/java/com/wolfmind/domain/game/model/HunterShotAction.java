package com.wolfmind.domain.game.model;

/**
 * 猎人开枪（domain-model.md §5.3）。
 *
 * <p>{@code target == null} 表示放弃开枪，产生 {@code HUNTER_SKIPPED}。目标为必填还是可空由
 * 本记录的类型允许，是否合法由阶段处理器与 {@code ActionValidator} 判定。
 */
public record HunterShotAction(SeatNo actor, SeatNo target) implements Action {

    public HunterShotAction {
        if (actor == null) {
            throw GameException.Factory.validation("HunterShotAction 需要 actor");
        }
    }

    public static HunterShotAction at(int actorSeat, int targetSeat) {
        return new HunterShotAction(SeatNo.of(actorSeat), SeatNo.of(targetSeat));
    }

    /** 放弃开枪。 */
    public static HunterShotAction giveUp(int actorSeat) {
        return new HunterShotAction(SeatNo.of(actorSeat), null);
    }

    public static HunterShotAction resolve(int actorSeat, Integer targetSeat) {
        return targetSeat == null ? giveUp(actorSeat) : at(actorSeat, targetSeat.intValue());
    }

    public boolean isGiveUp() {
        return target == null;
    }

    @Override
    public ActionType type() {
        return ActionType.HUNTER_SHOOT;
    }
}
