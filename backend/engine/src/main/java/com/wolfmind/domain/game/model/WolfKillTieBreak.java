package com.wolfmind.domain.game.model;

/** 狼刀提名平票的裁决方式（domain-model.md §3）。 */
public enum WolfKillTieBreak {
    /** 取座位号最小者，保证确定性（默认）。 */
    MIN_SEAT,
    /** 取座位号最大者。 */
    MAX_SEAT
}
