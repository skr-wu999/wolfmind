package com.wolfmind.domain.game.model;

/** 女巫行动模式（domain-model.md §5.3）。 */
public enum WitchMode {
    /** 使用解药。 */
    SAVE,
    /** 使用毒药。 */
    POISON,
    /** 本夜不用药。 */
    PASS
}
