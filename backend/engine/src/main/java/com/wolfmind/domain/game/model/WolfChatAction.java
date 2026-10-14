package com.wolfmind.domain.game.model;

/**
 * 狼人夜聊：{@code NIGHT_WOLF} 阶段存活狼人之间的密谈。
 *
 * <p>它不是提名，也不推进阶段：只留下一条 {@code WOLF} 可见的事实，供狼队与赛后回放阅读。
 */
public record WolfChatAction(SeatNo actor, Speech message) implements Action {

    /** 单条消息的最大长度（去首尾空白后）。 */
    public static final int MAX_LENGTH = 500;

    /** 每名狼人每晚最多发送的条数。 */
    public static final int MAX_PER_NIGHT = 20;

    public WolfChatAction {
        if (actor == null || message == null) {
            throw GameException.Factory.validation("WolfChatAction 需要 actor 与 message");
        }
    }

    public static WolfChatAction of(int seatNo, String content) {
        return new WolfChatAction(SeatNo.of(seatNo), Speech.of(content));
    }

    public String content() {
        return message.content();
    }

    @Override
    public ActionType type() {
        return ActionType.WOLF_CHAT;
    }
}
