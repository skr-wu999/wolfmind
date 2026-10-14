package com.wolfmind.domain.game.model;

/**
 * 事件可见性（docs/architecture/event-design.md §4）。
 *
 * <p>可见性是事件的属性而不是推送层的临时判断：回放、AI 工具、WebSocket 推送共用同一份判定，
 * 避免"另外写一份过滤逻辑"（AP-6）。
 */
public enum Visibility {

    /** 所有订阅者可见。 */
    PUBLIC,
    /** 仅狼人阵营可见。 */
    WOLF,
    /** 仅 {@code payload.playerId} 指定的玩家可见。 */
    PRIVATE,
    /** 系统、观战者与赛后回放可见；进行中的参与者不可见。 */
    JUDGE;

    /** 赛后回放是否可见：对局结束后全部可见。 */
    public boolean isRevealedAfterGame() {
        return true;
    }
}
