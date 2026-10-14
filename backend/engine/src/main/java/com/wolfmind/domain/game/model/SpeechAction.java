package com.wolfmind.domain.game.model;

/** 发言：白天讨论或遗言。 */
public record SpeechAction(SeatNo actor, Speech speech) implements Action {

    public SpeechAction {
        if (actor == null || speech == null) {
            throw GameException.Factory.validation("SpeechAction 需要 actor 与 speech");
        }
    }

    public static SpeechAction of(int seatNo, String content) {
        return new SpeechAction(SeatNo.of(seatNo), Speech.of(content));
    }

    public String content() {
        return speech.content();
    }

    @Override
    public ActionType type() {
        return ActionType.SPEAK;
    }
}
