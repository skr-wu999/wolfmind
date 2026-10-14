package com.wolfmind.domain.game.model;

/**
 * 行动类型：外部输入的枚举名（api-spec.md §5.3 的 {@code actionType}）。
 *
 * <p>Action 与事件是两套类型：外部输入只有 Action（REST 的 {@code actionType} 与工具调用都映射到
 * Action），事件只由领域产生（domain-model.md §5.3）。本枚举是接口层与领域之间唯一的字符串契约。
 */
public enum ActionType {

    /** 发言（白天讨论与遗言）。 */
    SPEAK,
    /** 投票或弃权。 */
    VOTE,
    /** 狼人提名击杀目标或空刀。 */
    WOLF_KILL,
    /** 预言家查验。 */
    SEER_INSPECT,
    /** 女巫使用解药。 */
    WITCH_SAVE,
    /** 女巫使用毒药。 */
    WITCH_POISON,
    /** 女巫本夜不用药。 */
    WITCH_PASS,
    /** 猎人开枪或放弃。 */
    HUNTER_SHOOT,
    /** 跳过：超时与主动跳过共用。 */
    SKIP,
    /** 狼人夜聊：只在狼队内可见，不推进阶段。 */
    WOLF_CHAT
}
