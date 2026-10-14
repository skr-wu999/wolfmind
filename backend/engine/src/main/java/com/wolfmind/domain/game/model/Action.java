package com.wolfmind.domain.game.model;

/**
 * 玩家意图的领域表达（domain-model.md §5.3）。
 *
 * <p>Action 是"玩家意图"，不是"状态变更指令"：意图是否被接受由 {@code ActionValidator}
 * 与阶段处理器决定。具体行动记录类都实现本接口。
 *
 * <p>所有行动都带 {@code actor} 座位号与对应的 {@link ActionType}；提交者身份由
 * {@link ActionContext#actorId()} 携带，二者必须匹配（INV-3，由应用层校验 controller）。
 */
public sealed interface Action permits SpeechAction, VoteAction, WolfKillAction,
        SeerInspectAction, WitchAction, HunterShotAction, SkipAction, WolfChatAction {

    /** 行动者座位。 */
    SeatNo actor();

    /** 行动类型（外部契约名）。 */
    ActionType type();
}
