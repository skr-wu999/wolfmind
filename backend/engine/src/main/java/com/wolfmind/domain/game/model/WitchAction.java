package com.wolfmind.domain.game.model;

/**
 * 女巫用药（domain-model.md §5.3）。
 *
 * <p>{@code mode = PASS} 时 {@code target} 必须为 {@code null}；{@code SAVE} / {@code POISON}
 * 必须给出目标。
 */
public record WitchAction(SeatNo actor, WitchMode mode, SeatNo target) implements Action {

    public WitchAction {
        if (actor == null || mode == null) {
            throw GameException.Factory.validation("WitchAction 需要 actor 与 mode");
        }
        if (mode == WitchMode.PASS && target != null) {
            throw GameException.Factory.validation("PASS 模式不得携带目标");
        }
        if (mode != WitchMode.PASS && target == null) {
            throw GameException.Factory.validation(mode + " 模式必须携带目标");
        }
    }

    public static WitchAction save(int actorSeat, int targetSeat) {
        return new WitchAction(SeatNo.of(actorSeat), WitchMode.SAVE, SeatNo.of(targetSeat));
    }

    public static WitchAction poison(int actorSeat, int targetSeat) {
        return new WitchAction(SeatNo.of(actorSeat), WitchMode.POISON, SeatNo.of(targetSeat));
    }

    public static WitchAction pass(int actorSeat) {
        return new WitchAction(SeatNo.of(actorSeat), WitchMode.PASS, null);
    }

    @Override
    public ActionType type() {
        return switch (mode) {
            case SAVE -> ActionType.WITCH_SAVE;
            case POISON -> ActionType.WITCH_POISON;
            case PASS -> ActionType.WITCH_PASS;
        };
    }
}
