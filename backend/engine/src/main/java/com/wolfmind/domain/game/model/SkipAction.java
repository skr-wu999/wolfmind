package com.wolfmind.domain.game.model;

/**
 * 跳过（domain-model.md §5.3）：超时与主动跳过共用的表达。
 *
 * <p>它同样会落到具体事件（{@code SPEECH_SKIPPED} / {@code WITCH_PASSED} / {@code HUNTER_SKIPPED} /
 * 弃权票 / 空刀），因此超时不绕过领域（C-4）。
 */
public record SkipAction(SeatNo actor, SkipReason reason) implements Action {

    public SkipAction {
        if (actor == null || reason == null) {
            throw GameException.Factory.validation("SkipAction 需要 actor 与 reason");
        }
    }

    public static SkipAction timeout(int actorSeat) {
        return new SkipAction(SeatNo.of(actorSeat), SkipReason.TIMEOUT);
    }

    public static SkipAction voluntary(int actorSeat) {
        return new SkipAction(SeatNo.of(actorSeat), SkipReason.VOLUNTARY);
    }

    public static SkipAction of(int actorSeat, SkipReason reason) {
        return new SkipAction(SeatNo.of(actorSeat), reason);
    }

    @Override
    public ActionType type() {
        return ActionType.SKIP;
    }
}
