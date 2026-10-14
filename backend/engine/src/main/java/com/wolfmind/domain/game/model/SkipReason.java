package com.wolfmind.domain.game.model;

/**
 * 跳过原因。
 *
 * <p>超时是合法输入而非异常：{@code TIMEOUT} 与 {@code VOLUNTARY} 走同一条 {@code handle} 路径，
 * 都落到具体事件（{@code SPEECH_SKIPPED} / {@code WITCH_PASSED} / 弃权票 / 空刀），
 * 因此"AI 卡死"不会绕过领域，重放结果唯一（C-4）。
 */
public enum SkipReason {
    /** 回合或阶段超时，由应用层超时看护提交。 */
    TIMEOUT,
    /** 玩家主动跳过。 */
    VOLUNTARY
}
